/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.stress;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.Uninterruptibles;

import org.apache.cassandra.stress.generate.Partition;
import org.apache.cassandra.stress.operations.OpDistribution;
import org.apache.cassandra.stress.operations.OpDistributionFactory;
import org.apache.cassandra.stress.settings.*;
import org.apache.cassandra.stress.util.JavaDriverClient;
import org.apache.cassandra.stress.util.ThriftClient;
import org.apache.cassandra.stress.util.Timer;
import org.apache.cassandra.transport.SimpleClient;

public class StressAction implements Runnable
{

    private final StressSettings settings;
    private final PrintStream output;

    public StressAction(StressSettings settings, PrintStream out)
    {
        this.settings = settings;
        output = out;
    }

    public void run()
    {
        // creating keyspace and column families
        settings.maybeCreateKeyspaces();

        // TODO: warmup should
        if (!settings.command.noWarmup)
            warmup(settings.command.getFactory(settings));

        output.println("Sleeping 2s...");
        Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);

        boolean success;
        if (settings.rate.auto)
            success = runAuto();
        else
            success = null != run(settings.command.getFactory(settings), settings.rate.threadCount, settings.command.count,
                                  settings.command.duration, settings.command.durationUnits, output);

        if (success)
            output.println("END");
        else
            output.println("FAILURE");

        settings.disconnect();
    }

    // type provided separately to support recursive call for mixed command with each command type it is performing
    private void warmup(OpDistributionFactory operations)
    {
        // warmup - do 50k iterations; by default hotspot compiles methods after 10k invocations
        PrintStream warmupOutput = new PrintStream(new OutputStream() { @Override public void write(int b) throws IOException { } } );
        int iterations = 50000 * settings.node.nodes.size();
        for (OpDistributionFactory single : operations.each())
        {
            // we need to warm up all the nodes in the cluster ideally, but we may not be the only stress instance;
            // so warm up all the nodes we're speaking to only.
            output.println(String.format("Warming up %s with %d iterations...", single.desc(), iterations));
            run(single, 20, iterations, 0, null, warmupOutput);
        }
    }

    // TODO : permit varying more than just thread count
    // TODO : vary thread count based on percentage improvement of previous increment, not by fixed amounts
    private boolean runAuto()
    {
        int prevThreadCount = -1;
        int threadCount = settings.rate.minAutoThreads;
        List<StressMetrics> results = new ArrayList<>();
        List<String> runIds = new ArrayList<>();
        do
        {
            output.println(String.format("Running with %d threadCount", threadCount));

            StressMetrics result = run(settings.command.getFactory(settings), threadCount, settings.command.count,
                                       settings.command.duration, settings.command.durationUnits, output);
            if (result == null)
                return false;
            results.add(result);

            if (prevThreadCount > 0)
                System.out.println(String.format("Improvement over %d threadCount: %.0f%%",
                        prevThreadCount, 100 * averageImprovement(results, 1)));

            runIds.add(threadCount + " threadCount");
            prevThreadCount = threadCount;
            if (threadCount < 16)
                threadCount *= 2;
            else
                threadCount *= 1.5;

            if (!results.isEmpty() && threadCount > settings.rate.maxAutoThreads)
                break;

            if (settings.command.type.updates)
            {
                // pause an arbitrary period of time to let the commit log flush, etc. shouldn't make much difference
                // as we only increase load, never decrease it
                output.println("Sleeping for 15s");
                try
                {
                    Thread.sleep(15 * 1000);
                } catch (InterruptedException e)
                {
                    return false;
                }
            }
            // run until we have not improved throughput significantly for previous three runs
        } while (hasAverageImprovement(results, 3, 0) && hasAverageImprovement(results, 5, settings.command.targetUncertainty));

        // summarise all results
        StressMetrics.summarise(runIds, results, output);
        return true;
    }

    private boolean hasAverageImprovement(List<StressMetrics> results, int count, double minImprovement)
    {
        return results.size() < count + 1 || averageImprovement(results, count) >= minImprovement;
    }

    private double averageImprovement(List<StressMetrics> results, int count)
    {
        double improvement = 0;
        for (int i = results.size() - count ; i < results.size() ; i++)
        {
            double prev = results.get(i - 1).getTiming().getHistory().opRate();
            double cur = results.get(i).getTiming().getHistory().opRate();
            improvement += (cur - prev) / prev;
        }
        return improvement / count;
    }

    private StressMetrics run(OpDistributionFactory operations, int threadCount, long opCount, long duration, TimeUnit durationUnits, PrintStream output)
    {
        output.println(String.format("Running %s with %d threads %s",
                                     operations.desc(),
                                     threadCount,
                                     durationUnits != null ? duration + " " + durationUnits.toString().toLowerCase()
                                        : opCount > 0      ? "for " + opCount + " iteration"
                                                           : "until stderr of mean < " + settings.command.targetUncertainty));
        final WorkManager workManager;
        if (opCount < 0)
            workManager = new ContinuousWorkManager();
        else
            workManager = new FixedWorkManager(opCount);

        RateLimiter rateLimiter = null;
        // TODO : move this to a new queue wrapper that gates progress based on a poisson (or configurable) distribution
        if (settings.rate.opRateTargetPerSecond > 0)
            rateLimiter = RateLimiter.create(settings.rate.opRateTargetPerSecond);

        final StressMetrics metrics = new StressMetrics(output, settings.log.intervalMillis, settings);

        final CountDownLatch done = new CountDownLatch(threadCount);
        final Consumer[] consumers = new Consumer[threadCount];
        for (int i = 0; i < threadCount; i++)
            consumers[i] = new Consumer(operations, done, workManager, metrics, rateLimiter);

        // starting worker threadCount
        for (int i = 0; i < threadCount; i++)
            consumers[i].start();

        metrics.start();

        if (durationUnits != null)
        {
            Uninterruptibles.sleepUninterruptibly(duration, durationUnits);
            workManager.stop();
        }
        else if (opCount <= 0)
        {
            try
            {
                metrics.waitUntilConverges(settings.command.targetUncertainty,
                        settings.command.minimumUncertaintyMeasurements,
                        settings.command.maximumUncertaintyMeasurements);
            } catch (InterruptedException e) { }
            workManager.stop();
        }

        try
        {
            done.await();
            metrics.stop();
        }
        catch (InterruptedException e) {}

        if (metrics.wasCancelled())
            return null;

        metrics.summarise();

        boolean success = true;
        for (Consumer consumer : consumers)
            success &= consumer.success;

        if (!success)
            return null;

        return metrics;
    }

    private class Consumer extends Thread
    {

        private final OpDistribution operations;
        private final StressMetrics metrics;
        private final Timer timer;
        private final RateLimiter rateLimiter;
        private volatile boolean success = true;
        private final WorkManager workManager;
        private final CountDownLatch done;

        public Consumer(OpDistributionFactory operations, CountDownLatch done, WorkManager workManager, StressMetrics metrics, RateLimiter rateLimiter)
        {
            this.done = done;
            this.rateLimiter = rateLimiter;
            this.workManager = workManager;
            this.metrics = metrics;
            this.timer = metrics.getTiming().newTimer();
            this.operations = operations.get(timer);
        }

        public void run()
        {

            try
            {

                SimpleClient sclient = null;
                ThriftClient tclient = null;
                JavaDriverClient jclient = null;

                switch (settings.mode.api)
                {
                    case JAVA_DRIVER_NATIVE:
                        jclient = settings.getJavaDriverClient();
                        break;
                    case SIMPLE_NATIVE:
                        sclient = settings.getSimpleNativeClient();
                        break;
                    case THRIFT:
                    case THRIFT_SMART:
                        tclient = settings.getThriftClient();
                        break;
                    default:
                        throw new IllegalStateException();
                }

                int maxBatchSize = operations.maxBatchSize();
                Partition[] partitions = new Partition[maxBatchSize];
                while (true)
                {

                    // TODO: Operation should be able to ecapsulate much of this behaviour
                    Operation op = operations.next();
                    op.generator.reset();

                    int batchSize = workManager.takePermits(Math.max(1, (int) op.partitionCount.next()));
                    if (batchSize < 0)
                        break;

                    if (rateLimiter != null)
                        rateLimiter.acquire(batchSize);

                    int partitionCount = 0;
                    while (partitionCount < batchSize)
                    {
                        Partition p = op.generator.generate(op);
                        if (p == null)
                            break;
                        partitions[partitionCount++] = p;
                    }

                    if (partitionCount == 0)
                        break;

                    op.setPartitions(Arrays.asList(partitions).subList(0, partitionCount));

                    try
                    {
                        switch (settings.mode.api)
                        {
                            case JAVA_DRIVER_NATIVE:
                                op.run(jclient);
                                break;
                            case SIMPLE_NATIVE:
                                op.run(sclient);
                                break;
                            case THRIFT:
                            case THRIFT_SMART:
                            default:
                                op.run(tclient);
                        }
                    }
                    catch (Exception e)
                    {
                        if (output == null)
                        {
                            System.err.println(e.getMessage());
                            success = false;
                            System.exit(-1);
                        }

                        e.printStackTrace(output);
                        success = false;
                        workManager.stop();
                        metrics.cancel();
                        return;
                    }
                }
            }
            finally
            {
                done.countDown();
                timer.close();
            }

        }

    }

    private interface WorkManager
    {
        // -1 indicates consumer should terminate
        int takePermits(int count);

        // signal all consumers to terminate
        void stop();
    }

    private static final class FixedWorkManager implements WorkManager
    {

        final AtomicLong permits;

        public FixedWorkManager(long permits)
        {
            this.permits = new AtomicLong(permits);
        }

        @Override
        public int takePermits(int count)
        {
            while (true)
            {
                long cur = permits.get();
                if (cur == 0)
                    return -1;
                count = (int) Math.min(count, cur);
                long next = cur - count;
                if (permits.compareAndSet(cur, next))
                    return count;
            }
        }

        @Override
        public void stop()
        {
            permits.getAndSet(0);
        }
    }

    private static final class ContinuousWorkManager implements WorkManager
    {

        volatile boolean stop = false;

        @Override
        public int takePermits(int count)
        {
            if (stop)
                return -1;
            return count;
        }

        @Override
        public void stop()
        {
            stop = true;
        }

    }
}
