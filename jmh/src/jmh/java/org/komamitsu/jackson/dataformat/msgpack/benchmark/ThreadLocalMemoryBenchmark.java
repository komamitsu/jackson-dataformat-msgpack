//
// MessagePack for Java
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
package org.komamitsu.jackson.dataformat.msgpack.benchmark;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.util.JsonRecyclerPools;
import org.komamitsu.jackson.dataformat.msgpack.MessagePackFactory;
import org.komamitsu.jackson.dataformat.msgpack.MessagePackMapper;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Measures heap retained per idle thread after a generator or parser call, which is
 * Jackson's own BufferRecycler retention now that the library keeps no ThreadLocal of its own.
 *
 * Usage:
 *   ./gradlew :jmh:threadLocalMemory --args="[numThreads] [payloadKB] [mode]"
 *
 * mode: generator | parser | both (default: both)
 * Defaults: 1024 threads, 1024 KB payload.
 */
public class ThreadLocalMemoryBenchmark
{
    public static void main(String[] args) throws Exception
    {
        int numThreads = args.length > 0 ? Integer.parseInt(args[0]) : 1024;
        int payloadKB  = args.length > 1 ? Integer.parseInt(args[1]) : 1024;
        String mode    = args.length > 2 ? args[2] : "both";

        System.out.printf("Threads: %d  Payload: %d KB  Mode: %s%n", numThreads, payloadKB, mode);


        ObjectMapper mapper;
        switch (mode) {
            case "vanilla":
                mapper = new JsonMapper();
                break;
            case "vanilla-shared-pool":
                mapper = new JsonMapper(JsonFactory.builder()
                        .recyclerPool(JsonRecyclerPools.sharedBoundedPool())
                        .build());
                break;
            default:
                mapper = new MessagePackMapper(new MessagePackFactory());
                break;
        }
        byte[] payload = new byte[payloadKB * 1024];

        Runnable work;
        switch (mode) {
            case "generator":
                work = () -> {
                    try { mapper.writeValueAsBytes(payload); }
                    catch (Exception e) { throw new RuntimeException(e); }
                };
                break;
            case "parser": {
                byte[] pre;
                try { pre = mapper.writeValueAsBytes(payload); }
                catch (Exception e) { throw new RuntimeException(e); }
                final byte[] preSerializedPayload = pre;
                work = () -> {
                    try { mapper.readValue(preSerializedPayload, byte[].class); }
                    catch (Exception e) { throw new RuntimeException(e); }
                };
                break;
            }
            default:
                work = () -> {
                    try {
                        byte[] serialized = mapper.writeValueAsBytes(payload);
                        mapper.readValue(serialized, byte[].class);
                    }
                    catch (Exception e) { throw new RuntimeException(e); }
                };
        }

        measure(numThreads, work);
    }

    private static void measure(int numThreads, Runnable work) throws Exception
    {
        forceGc();
        long beforePool = usedHeap();

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        try {
            // Phase 1: start all pool threads with a no-op to establish the per-thread
            // baseline without any Jackson involvement.
            runAll(pool, numThreads, () -> {});
            forceGc();
            long afterThreads = usedHeap();

            // Phase 2: run the actual Jackson work on the same threads.
            runAll(pool, numThreads, work);
            forceGc();
            long afterWork = usedHeap();

            long threadDelta  = afterThreads - beforePool;
            long jacksonDelta = afterWork - afterThreads;
            System.out.printf("Before pool (baseline):                    %.2f MB%n",
                    toMB(beforePool));
            System.out.printf("After no-op  (threads alive, no Jackson):  %.2f MB  thread overhead: %.2f KB/thread%n",
                    toMB(afterThreads), toKB(threadDelta) / numThreads);
            System.out.printf("After Jackson work (threads alive):         %.2f MB  Jackson-only:    %.2f KB/thread%n",
                    toMB(afterWork), toKB(jacksonDelta) / numThreads);
        }
        finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void runAll(ExecutorService pool, int numThreads, Runnable work) throws Exception
    {
        Callable<Void> task = () -> { work.run(); return null; };
        List<Future<Void>> futures = pool.invokeAll(Collections.nCopies(numThreads, task));
        for (Future<Void> f : futures) {
            f.get();
        }
    }

    private static void forceGc() throws InterruptedException
    {
        for (int i = 0; i < 5; i++) { System.gc(); Thread.sleep(200); }
    }

    private static long usedHeap()
    {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        return mem.getHeapMemoryUsage().getUsed();
    }

    private static double toMB(long bytes) { return bytes / (1024.0 * 1024.0); }
    private static double toKB(long bytes) { return bytes / 1024.0; }
}
