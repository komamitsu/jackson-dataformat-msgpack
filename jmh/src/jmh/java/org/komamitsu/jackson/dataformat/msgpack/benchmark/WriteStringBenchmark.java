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

import org.komamitsu.jackson.dataformat.msgpack.MessagePackFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;

import java.util.concurrent.TimeUnit;

/**
 * writeString(String) throughput, which is MessagePackWriter.packString: the short single-pass
 * path (up to 31 chars) and the two-pass path, for ASCII and non-ASCII text. With the gc
 * profiler, alloc.rate.norm shows that encoding itself allocates nothing.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class WriteStringBenchmark
{
    private static final int WRITES_PER_INVOCATION = 500;

    private final MessagePackFactory factory = new MessagePackFactory();

    private final String shortAscii = "a typical property name";
    private final String shortNonAscii = "東京は日本の首都です";
    private final String longAscii = "Hello, World! This is a typical ASCII field value.";
    private final String longNonAscii = "東京は日本の首都です。This mixes CJK and ASCII.";

    @Benchmark
    public int shortAscii() throws Exception
    {
        return write(shortAscii);
    }

    @Benchmark
    public int shortNonAscii() throws Exception
    {
        return write(shortNonAscii);
    }

    @Benchmark
    public int longAscii() throws Exception
    {
        return write(longAscii);
    }

    @Benchmark
    public int longNonAscii() throws Exception
    {
        return write(longNonAscii);
    }

    private int write(String s) throws Exception
    {
        NopOutputStream out = new NopOutputStream();
        try (JsonGenerator gen = factory.createGenerator(ObjectWriteContext.empty(), out)) {
            // Root-level values, so the buffer is flushed instead of grown and the only
            // allocation left is the generator itself.
            for (int i = 0; i < WRITES_PER_INVOCATION; i++) {
                gen.writeString(s);
            }
        }
        return out.size();
    }
}
