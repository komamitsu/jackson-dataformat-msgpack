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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        }
    }

    // A String can hold more chars than fit in an int once multiplied by 3, and the in-place
    // check must not wrap into thinking such a String is small enough for the buffer.
    @Test
    public void theInPlaceCheckDoesNotOverflow()
    {
        assertTrue(MessagePackWriter.fitsInPlace(10, 8000));
        assertTrue(MessagePackWriter.fitsInPlace(2665, 8000));
        assertFalse(MessagePackWriter.fitsInPlace(2666, 8000));
        assertFalse(MessagePackWriter.fitsInPlace(800_000_000, 8000));
        assertFalse(MessagePackWriter.fitsInPlace(Integer.MAX_VALUE, 8000));
    }

    // The UTF-8 length of a very large String does not fit an int, and must be reported
    // rather than truncated into a header whose length wrapped.
    @Test
    public void aByteLengthBeyondIntIsRejected()
    {
        assertEquals(0, MessagePackWriter.checkedByteLength(0));
        assertEquals(Integer.MAX_VALUE, MessagePackWriter.checkedByteLength(Integer.MAX_VALUE));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MessagePackWriter.checkedByteLength(Integer.MAX_VALUE + 1L));
        assertTrue(e.getMessage().contains("too long"), e.getMessage());
    }

    // Guards the cheap pre-check that decides whether a name needs counting before it is
    // recorded: below this length the UTF-8 form cannot exceed an int, whatever the chars are.
    @Test
    public void onlyAHugeStringNeedsItsLengthCounted()
    {
        assertFalse(MessagePackWriter.mayExceedIntLength(0));
        assertFalse(MessagePackWriter.mayExceedIntLength(1000));
        assertFalse(MessagePackWriter.mayExceedIntLength(715_827_882));
        assertTrue(MessagePackWriter.mayExceedIntLength(715_827_883));
        assertTrue(MessagePackWriter.mayExceedIntLength(Integer.MAX_VALUE));
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
    public void containerHeadersPatchedFromAnyReservedLength() throws IOException
    {
        int[] counts = {0, 1, 15, 16, 255, 65535, 65536};
        // Hints reserve 1, 3 or 5 bytes; -1 reserves a 1-byte placeholder.
        int[] hints = {-1, 0, 15, 16, 65535, 65536};
        for (boolean map : new boolean[] {false, true}) {
            for (int count : counts) {
                int nils = map ? count * 2 : count;
                byte[] expected = expected(packer -> {
                    if (map) {
                        packer.packMapHeader(count);
                    }
                    else {
                        packer.packArrayHeader(count);
                    }
                    for (int i = 0; i < nils; i++) {
                        packer.packNil();
                    }
                }, true);
                for (int hint : hints) {
                    byte[] got = actual(writer -> {
                        int offset = writer.openContainer(map, hint);
                        int reserved = writer.position() - offset;
                        for (int i = 0; i < nils; i++) {
                            writer.packNil();
                        }
                        writer.closeContainer(map, offset, reserved, count);
                    }, true);
                    assertArrayEquals(expected, got, "map=" + map + " count=" + count + " hint=" + hint);
                }
            }
        }
    }

    @Test
    public void nestedContainersPatchedInnermostFirst() throws IOException
    {
        // [ {1: [0..19], 2: nil}, [ "x" * 20 ], 7 ]
        assertSameBytes(p -> {
            p.packArrayHeader(3);
            p.packMapHeader(2);
            p.packInt(1);
            p.packArrayHeader(20);
            for (int i = 0; i < 20; i++) {
                p.packInt(i);
            }
            p.packInt(2);
            p.packNil();
            p.packArrayHeader(20);
            for (int i = 0; i < 20; i++) {
                p.packString("x");
            }
            p.packInt(7);
        }, w -> {
            int root = w.openContainer(false, -1);
            int rootReserved = w.position() - root;
            int m = w.openContainer(true, -1);
            int mReserved = w.position() - m;
            w.packInt(1);
            int inner = w.openContainer(false, -1);
            int innerReserved = w.position() - inner;
            for (int i = 0; i < 20; i++) {
                w.packInt(i);
            }
            w.closeContainer(false, inner, innerReserved, 20);
            w.packInt(2);
            w.packNil();
            w.closeContainer(true, m, mReserved, 2);
            int second = w.openContainer(false, 20);
            int secondReserved = w.position() - second;
            for (int i = 0; i < 20; i++) {
                w.packString("x");
            }
            w.closeContainer(false, second, secondReserved, 20);
            w.packInt(7);
            w.closeContainer(false, root, rootReserved, 3);
        });
    }

    @Test
    public void holdModeGrowsInsteadOfFlushingAndReleasesBorrowedBufferOnce() throws IOException
    {
        IOContext ioContext = newIOContext();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessagePackWriter writer = new MessagePackWriter(ioContext, out, true);
        String chunk = "y".repeat(4000);

        int offset = writer.openContainer(false, -1);
        int reserved = writer.position() - offset;
        for (int i = 0; i < 5; i++) {
            writer.packString(chunk);
        }
        assertEquals(0, out.size(), "nothing reaches the stream while a container is open");
        writer.closeContainer(false, offset, reserved, 5);
        assertEquals(1 + 5 * (3 + 4000), writer.pending());
        writer.flush();
        assertEquals(writer.pending(), 0);

        assertArrayEquals(expected(p -> {
            p.packArrayHeader(5);
            for (int i = 0; i < 5; i++) {
                p.packString(chunk);
            }
        }, true), out.toByteArray());

        // The IOContext buffer was handed back when the writer grew past it, so it can be lent
        // out again. A second release must be a no-op rather than a double release.
        writer.release();
        writer.release();
        ioContext.allocWriteEncodingBuffer();
    }

    @Test
    public void flushWhileHoldingThrows() throws IOException
    {
        MessagePackWriter writer = new MessagePackWriter(newIOContext(), new ByteArrayOutputStream(), true);
        writer.openContainer(false, -1);
        assertThrows(IllegalStateException.class, writer::flush);
        writer.discardFrom(0);
        writer.flush();
        writer.release();
    }

    @Test
    public void discardDropsPendingBytes() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessagePackWriter writer = new MessagePackWriter(newIOContext(), out, true);
        writer.openContainer(true, -1);
        writer.packString("half written");
        assertEquals(1 + 1 + 12, writer.pending());
        writer.discardFrom(0);
        assertEquals(0, writer.pending());
        writer.packInt(1);
        writer.flush();
        assertArrayEquals(new byte[] {1}, out.toByteArray());
        writer.release();
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
