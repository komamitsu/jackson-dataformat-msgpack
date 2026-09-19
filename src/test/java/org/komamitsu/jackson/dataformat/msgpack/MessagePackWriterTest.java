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

import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import tools.jackson.core.ErrorReportConfiguration;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.io.ContentReference;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.util.BufferRecycler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Byte-for-byte parity of MessagePackWriter against msgpack-core's MessagePacker.
 */
public class MessagePackWriterTest
{
    interface Packing
    {
        void apply(MessagePacker packer) throws IOException;
    }

    interface Writing
    {
        void apply(MessagePackWriter writer) throws IOException;
    }

    static IOContext newIOContext()
    {
        return new IOContext(StreamReadConstraints.defaults(), StreamWriteConstraints.defaults(),
                ErrorReportConfiguration.defaults(), new BufferRecycler(), ContentReference.unknown(),
                false, JsonEncoding.UTF8);
    }

    private static byte[] expected(Packing packing, boolean str8FormatSupport) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessagePack.PackerConfig config = new MessagePack.PackerConfig().withStr8FormatSupport(str8FormatSupport);
        try (MessagePacker packer = config.newPacker(out)) {
            packing.apply(packer);
        }
        return out.toByteArray();
    }

    private static byte[] actual(Writing writing, boolean str8FormatSupport) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessagePackWriter writer = new MessagePackWriter(newIOContext(), out, str8FormatSupport);
        writing.apply(writer);
        writer.flush();
        writer.release();
        return out.toByteArray();
    }

    private static void assertSameBytes(Packing packing, Writing writing) throws IOException
    {
        assertArrayEquals(expected(packing, true), actual(writing, true));
        assertArrayEquals(expected(packing, false), actual(writing, false));
    }

    @Test
    public void nilAndBooleans() throws IOException
    {
        assertSameBytes(MessagePacker::packNil, MessagePackWriter::packNil);
        assertSameBytes(p -> p.packBoolean(true), w -> w.packBoolean(true));
        assertSameBytes(p -> p.packBoolean(false), w -> w.packBoolean(false));
    }

    @Test
    public void integersAtEveryFormatBoundary() throws IOException
    {
        long[] values = {
                0, 1, 127, 128, 255, 256, 65535, 65536, (1L << 32) - 1, 1L << 32, Long.MAX_VALUE,
                -1, -32, -33, -128, -129, -32768, -32769, Integer.MIN_VALUE, Integer.MIN_VALUE - 1L, Long.MIN_VALUE,
        };
        for (long v : values) {
            assertSameBytes(p -> p.packLong(v), w -> w.packLong(v));
            if (v == (int) v) {
                int i = (int) v;
                assertSameBytes(p -> p.packInt(i), w -> w.packInt(i));
            }
        }
    }

    @Test
    public void bigIntegers() throws IOException
    {
        BigInteger[] values = {
                BigInteger.ZERO,
                BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
                BigInteger.valueOf(Long.MIN_VALUE),
        };
        for (BigInteger v : values) {
            assertSameBytes(p -> p.packBigInteger(v), w -> w.packBigInteger(v));
        }
        BigInteger tooBig = BigInteger.ONE.shiftLeft(64);
        assertThrows(IllegalArgumentException.class, () -> actual(w -> w.packBigInteger(tooBig), true));
        BigInteger tooSmall = BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE);
        assertThrows(IllegalArgumentException.class, () -> actual(w -> w.packBigInteger(tooSmall), true));
    }

    @Test
    public void floats() throws IOException
    {
        float[] floats = {0f, -0f, 1.5f, Float.MAX_VALUE, Float.MIN_VALUE, Float.NaN, Float.NEGATIVE_INFINITY};
        for (float v : floats) {
            assertSameBytes(p -> p.packFloat(v), w -> w.packFloat(v));
        }
        double[] doubles = {0d, -0d, 3.14, Double.MAX_VALUE, Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY};
        for (double v : doubles) {
            assertSameBytes(p -> p.packDouble(v), w -> w.packDouble(v));
        }
    }

    @Test
    public void stringsAtEveryLengthBoundaryAndCharClass() throws IOException
    {
        int[] lengths = {0, 1, 31, 32, 255, 256, 65535, 65536, 100_000};
        String[] alphabets = {"a", "é", "三", "😀"};
        for (int len : lengths) {
            for (String alphabet : alphabets) {
                StringBuilder sb = new StringBuilder(len * alphabet.length());
                for (int i = 0; i < len; i++) {
                    sb.append(alphabet);
                }
                String s = sb.toString();
                assertSameBytes(p -> p.packString(s), w -> w.packString(s));
            }
        }
    }

    @Test
    public void stringsWithUnpairedSurrogates() throws IOException
    {
        String[] values = {"\ud83d", "\ude00", "a\ud83dz", "\ud83d\ud83d", "\ude00😀"};
        for (String s : values) {
            assertArrayEquals(s.getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(actual(w -> w.packString(s), true), 1, 1 + s.getBytes(StandardCharsets.UTF_8).length));
            assertSameBytes(p -> p.packString(s), w -> w.packString(s));
        }
    }

    @Test
    public void rawStringHeadersAndPayloads() throws IOException
    {
        int[] lengths = {0, 31, 32, 255, 256, 65535, 65536};
        for (int len : lengths) {
            byte[] payload = new byte[len];
            Arrays.fill(payload, (byte) 'x');
            assertSameBytes(p -> {
                p.packRawStringHeader(len);
                p.writePayload(payload);
            }, w -> {
                w.packRawStringHeader(len);
                w.writePayload(payload);
            });
        }
    }

    @Test
    public void binaryHeadersAndPayloads() throws IOException
    {
        int[] lengths = {0, 1, 255, 256, 65535, 65536, 100_000};
        for (int len : lengths) {
            byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) {
                payload[i] = (byte) i;
            }
            assertSameBytes(p -> {
                p.packBinaryHeader(len);
                p.writePayload(payload);
            }, w -> {
                w.packBinaryHeader(len);
                w.writePayload(payload);
            });
            assertSameBytes(p -> {
                p.packBinaryHeader(len);
                p.addPayload(payload);
            }, w -> {
                w.packBinaryHeader(len);
                w.addPayload(payload);
            });
        }
    }

    @Test
    public void payloadWithOffset() throws IOException
    {
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7);
        }
        int[][] slices = {{0, 5}, {3, 100}, {1, 9_000}, {10, 19_990}};
        for (int[] slice : slices) {
            assertSameBytes(p -> {
                p.packBinaryHeader(slice[1]);
                p.writePayload(payload, slice[0], slice[1]);
            }, w -> {
                w.packBinaryHeader(slice[1]);
                w.writePayload(payload, slice[0], slice[1]);
            });
        }
    }

    @Test
    public void containerHeaders() throws IOException
    {
        int[] sizes = {0, 1, 15, 16, 65535, 65536, Integer.MAX_VALUE};
        for (int size : sizes) {
            assertSameBytes(p -> p.packArrayHeader(size), w -> w.packArrayHeader(size));
            assertSameBytes(p -> p.packMapHeader(size), w -> w.packMapHeader(size));
        }
    }

    @Test
    public void extensionHeaders() throws IOException
    {
        int[] lengths = {0, 1, 2, 3, 4, 5, 8, 9, 16, 17, 255, 256, 65535, 65536};
        byte[] types = {0, 1, -1, 127, -128};
        for (int len : lengths) {
            for (byte type : types) {
                assertSameBytes(p -> p.packExtensionTypeHeader(type, len), w -> w.packExtensionTypeHeader(type, len));
            }
        }
    }

    @Test
    public void timestampsAtEveryFormatBoundary() throws IOException
    {
        long[] seconds = {
                0, 1, (1L << 32) - 1, 1L << 32, (1L << 34) - 1, 1L << 34, -1, Instant.MIN.getEpochSecond(), Instant.MAX.getEpochSecond(),
        };
        int[] nanos = {0, 1, 999_999_999};
        for (long sec : seconds) {
            for (int nano : nanos) {
                Instant instant = Instant.ofEpochSecond(sec, nano);
                assertSameBytes(p -> p.packTimestamp(instant), w -> w.packTimestamp(instant));
                assertSameBytes(p -> p.packTimestamp(sec, nano), w -> w.packTimestamp(sec, nano));

                byte[] full = actual(w -> w.packTimestamp(instant), true);
                byte[] payload = MessagePackWriter.timestampPayload(instant);
                int headerLen = full.length - payload.length;
                assertArrayEquals(Arrays.copyOfRange(full, headerLen, full.length), payload);
            }
        }
        // Nanosecond adjustment outside [0, 1e9) carries into the seconds.
        assertSameBytes(p -> p.packTimestamp(10, -1), w -> w.packTimestamp(10, -1));
        assertSameBytes(p -> p.packTimestamp(10, 2_000_000_001), w -> w.packTimestamp(10, 2_000_000_001));
    }

    @Test
    public void manyValuesCrossBufferBoundary() throws IOException
    {
        assertSameBytes(p -> {
            for (int i = 0; i < 50_000; i++) {
                p.packInt(i);
                p.packString("v" + i);
            }
        }, w -> {
            for (int i = 0; i < 50_000; i++) {
                w.packInt(i);
                w.packString("v" + i);
            }
        });
    }

    @Test
    public void flushWritesBufferedBytesAndClosePropagates() throws IOException
    {
        int[] closed = {0};
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException
            {
                closed[0]++;
                super.close();
            }
        };
        MessagePackWriter writer = new MessagePackWriter(newIOContext(), out, true);
        writer.packInt(42);
        assertEquals(0, out.size());
        writer.flush();
        assertEquals(1, out.size());
        writer.packInt(43);
        writer.close();
        assertEquals(2, out.size());
        assertEquals(1, closed[0]);
        writer.release();
    }
}
