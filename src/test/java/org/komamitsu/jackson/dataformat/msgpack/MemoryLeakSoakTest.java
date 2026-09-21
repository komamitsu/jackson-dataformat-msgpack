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
package org.komamitsu.jackson.dataformat.msgpack;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs one factory under sustained load from several threads and checks that the heap
 * settles: after a warm-up, the post-GC heap at the end must not exceed the post-GC heap at
 * the start by more than a fixed allowance. Every iteration uses property names never seen
 * before, so the shared symbol table would be the first thing to grow if it were unbounded,
 * and values long enough to make the writer replace its pooled buffer.
 *
 * <p>Not part of the normal build. Run with {@code ./gradlew soakTest -Psoak.seconds=120}.
 */
@Tag("soak")
public class MemoryLeakSoakTest
{
    private static final int THREADS = 4;
    private static final long ALLOWED_GROWTH_BYTES = 8L * 1024 * 1024;

    @Test
    public void heapDoesNotGrowUnderSustainedLoad() throws Exception
    {
        long seconds = Long.getLong("soak.seconds", 30);
        long warmUpSeconds = Math.max(2, seconds / 5);

        MessagePackFactory factory = new MessagePackFactory();
        ObjectMapper mapper = new MessagePackMapper(factory);

        long warmUpIterations = run(mapper, factory, warmUpSeconds);
        long afterWarmUp = settledHeap();
        long mainIterations = run(mapper, factory, seconds - warmUpSeconds);
        long atEnd = settledHeap();

        System.out.printf("soak: %d iterations on %d threads in %ds, heap after warm-up %d KB, at end %d KB%n",
                warmUpIterations + mainIterations, THREADS, seconds, afterWarmUp / 1024, atEnd / 1024);
        assertTrue(mainIterations > warmUpIterations, "the run was too short to mean anything");
        assertTrue(atEnd - afterWarmUp < ALLOWED_GROWTH_BYTES,
                "heap grew by " + (atEnd - afterWarmUp) / 1024 + " KB over " + mainIterations + " iterations");
    }

    // Runs the workload on all threads for the given time and returns the iteration count.
    private static long run(ObjectMapper mapper, MessagePackFactory factory, long seconds) throws Exception
    {
        long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        AtomicLong iterations = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> workers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int thread = t;
            workers.add(pool.submit(() -> {
                for (long i = 0; System.nanoTime() < stop; i++) {
                    oneIteration(mapper, factory, thread, i);
                    iterations.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> w : workers) {
            w.get();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        return iterations.get();
    }

    private static void oneIteration(ObjectMapper mapper, MessagePackFactory factory, int thread, long i) throws Exception
    {
        // Fresh names every time: a bounded symbol table must not keep them all.
        String prefix = "t" + thread + "i" + i + "_";
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(prefix + "int", i);
        value.put(prefix + "str", "value " + i);
        value.put(prefix + "nonAscii", "値" + i + " 😀");
        List<Object> list = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            list.add(prefix + "item" + k);
        }
        value.put(prefix + "list", list);
        // Larger than the pooled 8000-byte buffer, inside a container, so the writer must grow.
        value.put(prefix + "long", longString(i));

        byte[] bytes = mapper.writeValueAsBytes(value);
        Map<String, Object> fromArray = mapper.readValue(bytes, new TypeReference<Map<String, Object>>() {});
        assertEquals(value.size(), fromArray.size());
        Map<String, Object> fromStream = mapper.readValue(new ByteArrayInputStream(bytes), new TypeReference<Map<String, Object>>() {});
        assertEquals(value.get(prefix + "long"), fromStream.get(prefix + "long"));

        // The abnormal paths every few iterations: a generator closed with containers open
        // (both close policies) and a parser closed mid-stream.
        if (i % 16 == 0) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (JsonGenerator gen = factory.createGenerator(ObjectWriteContext.empty(), out)) {
                gen.writeStartObject();
                gen.writeName(prefix + "dangling");
                gen.writeStartArray();
                gen.writeString(longString(i));
            }
            MessagePackFactory noAutoClose = (MessagePackFactory) factory.rebuild()
                    .disable(StreamWriteFeature.AUTO_CLOSE_CONTENT).build();
            try (JsonGenerator gen = noAutoClose.createGenerator(ObjectWriteContext.empty(), new ByteArrayOutputStream())) {
                gen.writeStartArray();
                gen.writeString(longString(i));
            }
            try (JsonParser p = factory.createParser(ObjectReadContext.empty(), new ByteArrayInputStream(bytes))) {
                assertEquals(JsonToken.START_OBJECT, p.nextToken());
                assertEquals(JsonToken.PROPERTY_NAME, p.nextToken());
            }
        }
    }

    private static String longString(long seed)
    {
        StringBuilder sb = new StringBuilder(12_000);
        while (sb.length() < 12_000) {
            sb.append("payload ").append(seed).append(' ');
        }
        return sb.toString();
    }

    // Post-GC heap, taking the smallest of a few readings since a single GC is not a guarantee.
    private static long settledHeap() throws InterruptedException
    {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(100);
            best = Math.min(best, memory.getHeapMemoryUsage().getUsed());
        }
        return best;
    }
}
