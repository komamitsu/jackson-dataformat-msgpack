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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MessagePackReader decodes what msgpack-core's MessagePacker produces, from both a byte
 * array and a stream, and tracks consumed bytes the way MessageUnpacker does.
 */
public class MessagePackReaderTest
{
    interface Packing
    {
        void apply(MessagePacker packer) throws IOException;
    }

    interface Reading
    {
        void apply(MessagePackReader reader) throws IOException;
    }

    private static byte[] pack(Packing packing) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MessagePacker packer = MessagePack.newDefaultPacker(out)) {
            packing.apply(packer);
        }
        return out.toByteArray();
    }

    // A stream that hands out one byte at a time, to exercise every refill path.
    private static InputStream trickle(byte[] data)
    {
        return new ByteArrayInputStream(data) {
            @Override
            public int read(byte[] b, int off, int len)
            {
                return super.read(b, off, Math.min(len, 1));
            }
        };
    }

    private static final Function<byte[], MessagePackReader>[] SOURCES = sources();

    @SuppressWarnings("unchecked")
    private static Function<byte[], MessagePackReader>[] sources()
    {
        return new Function[] {
                (Function<byte[], MessagePackReader>) data -> new MessagePackReader(data, 0, data.length),
                (Function<byte[], MessagePackReader>) data -> {
                    // Surround with junk so offset handling is exercised.
                    byte[] padded = new byte[data.length + 7];
                    Arrays.fill(padded, (byte) 0xc1);
                    System.arraycopy(data, 0, padded, 3, data.length);
                    return new MessagePackReader(padded, 3, data.length);
                },
                (Function<byte[], MessagePackReader>) data ->
                        new MessagePackReader(MessagePackWriterTest.newIOContext(), new ByteArrayInputStream(data), true),
                (Function<byte[], MessagePackReader>) data ->
                        new MessagePackReader(MessagePackWriterTest.newIOContext(), trickle(data), false),
        };
    }

    private static void forEachSource(byte[] data, Reading reading) throws IOException
    {
        for (Function<byte[], MessagePackReader> source : SOURCES) {
            MessagePackReader reader = source.apply(data);
            reading.apply(reader);
            assertFalse(reader.hasNext(), "all input should be consumed");
            assertEquals(data.length, reader.getTotalReadBytes());
            reader.close();
            reader.release();
        }
    }

    @Test
    public void nilAndBooleans() throws IOException
    {
        forEachSource(pack(p -> p.packNil().packBoolean(true).packBoolean(false)), r -> {
            assertEquals(MessageFormat.NIL, r.getNextFormat());
            r.unpackNil();
            assertEquals(MessageFormat.BOOLEAN, r.getNextFormat());
            assertTrue(r.unpackBoolean());
            assertFalse(r.unpackBoolean());
        });
    }

    @Test
    public void integersAtEveryFormatBoundary() throws IOException
    {
        long[] values = {
                0, 1, 127, 128, 255, 256, 65535, 65536, (1L << 32) - 1, 1L << 32, Long.MAX_VALUE,
                -1, -32, -33, -128, -129, -32768, -32769, Integer.MIN_VALUE, Integer.MIN_VALUE - 1L, Long.MIN_VALUE,
        };
        MessageFormat[] formats = {
                MessageFormat.POSFIXINT, MessageFormat.POSFIXINT, MessageFormat.POSFIXINT, MessageFormat.UINT8,
                MessageFormat.UINT8, MessageFormat.UINT16, MessageFormat.UINT16, MessageFormat.UINT32,
                MessageFormat.UINT32, MessageFormat.UINT64, MessageFormat.UINT64,
                MessageFormat.NEGFIXINT, MessageFormat.NEGFIXINT, MessageFormat.INT8, MessageFormat.INT8,
                MessageFormat.INT16, MessageFormat.INT16, MessageFormat.INT32, MessageFormat.INT32,
                MessageFormat.INT64, MessageFormat.INT64,
        };
        forEachSource(pack(p -> {
            for (long v : values) {
                p.packLong(v);
            }
        }), r -> {
            for (int i = 0; i < values.length; i++) {
                assertEquals(formats[i], r.getNextFormat(), "format of " + values[i]);
                assertEquals(MessageFormat.ValueType.INTEGER, r.getNextFormat().getValueType());
                assertEquals(values[i], r.unpackLong());
            }
        });
        forEachSource(pack(p -> {
            for (long v : values) {
                p.packLong(v);
            }
        }), r -> {
            for (long v : values) {
                assertEquals(BigInteger.valueOf(v), r.unpackBigInteger());
            }
        });
    }

    @Test
    public void unsignedLongsBeyondLongRange() throws IOException
    {
        BigInteger[] values = {
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
        };
        forEachSource(pack(p -> {
            for (BigInteger v : values) {
                p.packBigInteger(v);
            }
        }), r -> {
            for (BigInteger v : values) {
                assertEquals(MessageFormat.UINT64, r.getNextFormat());
                assertEquals(v, r.unpackBigInteger());
            }
        });
        byte[] data = pack(p -> p.packBigInteger(values[0]));
        MessagePackReader reader = new MessagePackReader(data, 0, data.length);
        assertThrows(IOException.class, reader::unpackLong);
    }

    @Test
    public void floats() throws IOException
    {
        forEachSource(pack(p -> p.packFloat(1.5f).packDouble(3.25).packFloat(Float.NaN).packDouble(-0.0)), r -> {
            assertEquals(MessageFormat.FLOAT32, r.getNextFormat());
            assertEquals(1.5, r.unpackDouble());
            assertEquals(MessageFormat.FLOAT64, r.getNextFormat());
            assertEquals(3.25, r.unpackDouble());
            assertTrue(Double.isNaN(r.unpackDouble()));
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(r.unpackDouble()));
        });
    }

    @Test
    public void stringsAtEveryLengthBoundary() throws IOException
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
                forEachSource(pack(p -> p.packString(s)), r -> {
                    assertEquals(MessageFormat.ValueType.STRING, r.getNextFormat().getValueType());
                    assertEquals(s, r.unpackString());
                });
            }
        }
    }

    @Test
    public void malformedUtf8IsReplacedLikeMsgpackCore() throws IOException
    {
        byte[] data = {(byte) 0xa3, (byte) 0xff, (byte) 0xfe, 'a'};
        String expected;
        try (var unpacker = MessagePack.newDefaultUnpacker(data)) {
            expected = unpacker.unpackString();
        }
        forEachSource(data, r -> assertEquals(expected, r.unpackString()));
    }

    @Test
    public void binaryAtEveryLengthBoundary() throws IOException
    {
        int[] lengths = {0, 1, 255, 256, 65535, 65536, 100_000};
        for (int len : lengths) {
            byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) {
                payload[i] = (byte) (i * 3);
            }
            forEachSource(pack(p -> p.packBinaryHeader(len).writePayload(payload)), r -> {
                assertEquals(MessageFormat.ValueType.BINARY, r.getNextFormat().getValueType());
                int n = r.unpackBinaryHeader();
                assertEquals(len, n);
                assertArrayEquals(payload, r.readPayload(n));
            });
        }
    }

    @Test
    public void containerHeaders() throws IOException
    {
        int[] sizes = {0, 1, 15, 16, 65535, 65536, Integer.MAX_VALUE};
        MessageFormat[] arrayFormats = {
                MessageFormat.FIXARRAY, MessageFormat.FIXARRAY, MessageFormat.FIXARRAY, MessageFormat.ARRAY16,
                MessageFormat.ARRAY16, MessageFormat.ARRAY32, MessageFormat.ARRAY32,
        };
        MessageFormat[] mapFormats = {
                MessageFormat.FIXMAP, MessageFormat.FIXMAP, MessageFormat.FIXMAP, MessageFormat.MAP16,
                MessageFormat.MAP16, MessageFormat.MAP32, MessageFormat.MAP32,
        };
        forEachSource(pack(p -> {
            for (int size : sizes) {
                p.packArrayHeader(size).packMapHeader(size);
            }
        }), r -> {
            for (int i = 0; i < sizes.length; i++) {
                assertEquals(arrayFormats[i], r.getNextFormat());
                assertEquals(sizes[i], r.unpackArrayHeader());
                assertEquals(mapFormats[i], r.getNextFormat());
                assertEquals(sizes[i], r.unpackMapHeader());
            }
        });
    }

    @Test
    public void extensionHeadersAndPayloads() throws IOException
    {
        int[] lengths = {0, 1, 2, 3, 4, 5, 8, 9, 16, 17, 255, 256, 65535, 65536};
        byte[] types = {0, 1, -1, 127, -128};
        for (int len : lengths) {
            for (byte type : types) {
                byte[] payload = new byte[len];
                Arrays.fill(payload, (byte) 0x5a);
                forEachSource(pack(p -> p.packExtensionTypeHeader(type, len).writePayload(payload)), r -> {
                    assertEquals(MessageFormat.ValueType.EXTENSION, r.getNextFormat().getValueType());
                    ExtensionTypeHeader header = r.unpackExtensionTypeHeader();
                    assertEquals(type, header.getType());
                    assertEquals(len, header.getLength());
                    assertArrayEquals(payload, r.readPayload(header.getLength()));
                });
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
                forEachSource(pack(p -> p.packTimestamp(instant)), r -> {
                    ExtensionTypeHeader header = r.unpackExtensionTypeHeader();
                    assertEquals(-1, header.getType());
                    assertEquals(instant, r.unpackTimestamp(header));
                });
                // The payload-only form used by MessagePackExtensionType.
                byte[] payload = MessagePackWriter.timestampPayload(instant);
                MessagePackReader reader = new MessagePackReader(payload, 0, payload.length);
                assertEquals(instant, reader.unpackTimestamp(new ExtensionTypeHeader((byte) -1, payload.length)));
            }
        }
    }

    // The spec caps the nanosecond field at 999999999; Instant would silently carry a larger
    // value into the seconds, turning malformed input into a different timestamp.
    @Test
    public void timestampNanosecondsOutOfRangeAreRejected() throws IOException
    {
        // timestamp64: 30-bit nsec in the top bits. 1_000_000_000 fits in 30 bits.
        byte[] ts64 = new byte[8];
        long data64 = (1_000_000_000L << 34) | 1L;
        for (int i = 0; i < 8; i++) {
            ts64[i] = (byte) (data64 >>> (56 - 8 * i));
        }
        // timestamp96: 32-bit nsec first, then 64-bit sec. Both a value just past the cap
        // and one with the sign bit set, which is unsigned per the spec.
        for (int nsec : new int[] {1_000_000_000, 0x80000000}) {
            byte[] ts96 = new byte[12];
            for (int i = 0; i < 4; i++) {
                ts96[i] = (byte) (nsec >>> (24 - 8 * i));
            }
            MessagePackReader r96 = new MessagePackReader(ts96, 0, 12);
            assertThrows(IOException.class, () -> r96.unpackTimestamp(new ExtensionTypeHeader((byte) -1, 12)));
        }
        MessagePackReader r64 = new MessagePackReader(ts64, 0, 8);
        assertThrows(IOException.class, () -> r64.unpackTimestamp(new ExtensionTypeHeader((byte) -1, 8)));

        // The largest legal value is still fine in both forms.
        Instant max = Instant.ofEpochSecond(1, 999_999_999);
        byte[] ok64 = MessagePackWriter.timestampPayload(max);
        assertEquals(8, ok64.length);
        assertEquals(max, new MessagePackReader(ok64, 0, 8).unpackTimestamp(new ExtensionTypeHeader((byte) -1, 8)));
        byte[] ok96 = MessagePackWriter.timestampPayload(Instant.ofEpochSecond(1L << 40, 999_999_999));
        assertEquals(12, ok96.length);
        assertEquals(Instant.ofEpochSecond(1L << 40, 999_999_999),
                new MessagePackReader(ok96, 0, 12).unpackTimestamp(new ExtensionTypeHeader((byte) -1, 12)));
    }

    @Test
    public void totalReadBytesTracksConsumption() throws IOException
    {
        byte[] data = pack(p -> p.packInt(1).packString("hello").packArrayHeader(2).packNil().packInt(100_000));
        forEachSource(data, r -> {
            assertEquals(0, r.getTotalReadBytes());
            r.unpackLong();
            assertEquals(1, r.getTotalReadBytes());
            r.unpackString();
            assertEquals(7, r.getTotalReadBytes());
            r.unpackArrayHeader();
            assertEquals(8, r.getTotalReadBytes());
            r.unpackNil();
            assertEquals(9, r.getTotalReadBytes());
            r.unpackLong();
            assertEquals(14, r.getTotalReadBytes());
        });
    }

    @Test
    public void totalReadBytesAcrossLargePayloadFromStream() throws IOException
    {
        byte[] payload = new byte[30_000];
        byte[] data = pack(p -> p.packInt(7).packBinaryHeader(payload.length).writePayload(payload).packInt(8));
        forEachSource(data, r -> {
            r.unpackLong();
            int len = r.unpackBinaryHeader();
            assertEquals(4, r.getTotalReadBytes());
            r.readPayload(len);
            assertEquals(4 + payload.length, r.getTotalReadBytes());
            assertEquals(8, r.unpackLong());
        });
    }

    @Test
    public void truncatedInputThrowsEofForEveryValueKind() throws IOException
    {
        byte[][] wholes = {
                pack(p -> p.packInt(100_000)),
                pack(p -> p.packLong(Long.MAX_VALUE)),
                pack(p -> p.packDouble(1.0)),
                pack(p -> p.packString("hello world, this is longer than a fixstr allows....")),
                pack(p -> p.packBinaryHeader(300).writePayload(new byte[300])),
                pack(p -> p.packArrayHeader(70_000)),
                pack(p -> p.packMapHeader(70_000)),
                pack(p -> p.packExtensionTypeHeader((byte) 3, 300).writePayload(new byte[300])),
                pack(p -> p.packTimestamp(Instant.ofEpochSecond(1L << 40, 5))),
        };
        for (byte[] whole : wholes) {
            for (int cut = 1; cut < whole.length; cut++) {
                byte[] truncated = Arrays.copyOf(whole, cut);
                for (Function<byte[], MessagePackReader> source : SOURCES) {
                    MessagePackReader reader = source.apply(truncated);
                    assertTrue(reader.hasNext());
                    assertThrows(EOFException.class, () -> drainOneValue(reader), "cut at " + cut);
                }
            }
        }
    }

    private static void drainOneValue(MessagePackReader r) throws IOException
    {
        switch (r.getNextFormat().getValueType()) {
            case INTEGER:
                r.unpackBigInteger();
                break;
            case FLOAT:
                r.unpackDouble();
                break;
            case STRING:
                r.unpackString();
                break;
            case BINARY:
                r.readPayload(r.unpackBinaryHeader());
                break;
            case ARRAY:
                r.unpackArrayHeader();
                break;
            case MAP:
                r.unpackMapHeader();
                break;
            case EXTENSION:
                ExtensionTypeHeader h = r.unpackExtensionTypeHeader();
                r.readPayload(h.getLength());
                break;
            default:
                throw new AssertionError();
        }
    }

    @Test
    public void withoutReadAheadTheStreamStopsAtTheEndOfEachValue() throws IOException
    {
        byte[] data = pack(p -> p.packString("first value").packInt(300).packArrayHeader(1).packNil());
        ByteArrayInputStream stream = new ByteArrayInputStream(data);

        MessagePackReader r1 = new MessagePackReader(MessagePackWriterTest.newIOContext(), stream, false);
        assertEquals("first value", r1.unpackString());
        r1.release();
        assertEquals(data.length - 12, stream.available(), "exactly the string was consumed");

        MessagePackReader r2 = new MessagePackReader(MessagePackWriterTest.newIOContext(), stream, false);
        assertEquals(300, r2.unpackLong());
        r2.release();
        assertEquals(2, stream.available());

        MessagePackReader r3 = new MessagePackReader(MessagePackWriterTest.newIOContext(), stream, false);
        assertEquals(1, r3.unpackArrayHeader());
        r3.unpackNil();
        assertFalse(r3.hasNext());
        r3.release();
    }

    // A binary value goes through readPayload, which the small values of the test above do
    // not exercise. Both a payload that fits the buffer and one that does not must leave the
    // stream exactly at the end of the value.
    @Test
    public void withoutReadAheadAPayloadStopsAtTheEndOfTheValue() throws IOException
    {
        for (int size : new int[] {5_000, 30_000}) {
            byte[] payload = new byte[size];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) i;
            }
            byte[] data = pack(p -> p.packBinaryHeader(payload.length).writePayload(payload).packInt(7));
            ByteArrayInputStream stream = new ByteArrayInputStream(data);

            MessagePackReader r1 = new MessagePackReader(MessagePackWriterTest.newIOContext(), stream, false);
            int len = r1.unpackBinaryHeader();
            assertArrayEquals(payload, r1.readPayload(len));
            assertEquals(data.length - 1, r1.getTotalReadBytes());
            r1.release();
            assertEquals(1, stream.available(), "only the binary value was consumed");

            MessagePackReader r2 = new MessagePackReader(MessagePackWriterTest.newIOContext(), stream, false);
            assertEquals(7, r2.unpackLong());
            assertFalse(r2.hasNext());
            r2.release();
        }
    }

    @Test
    public void withReadAheadTheStreamIsBuffered() throws IOException
    {
        byte[] data = pack(p -> p.packString("first value").packInt(300));
        ByteArrayInputStream stream = new ByteArrayInputStream(data);
        MessagePackReader reader = new MessagePackReader(MessagePackWriterTest.newIOContext(), stream, true);
        assertEquals("first value", reader.unpackString());
        assertEquals(0, stream.available(), "read ahead drains a small stream in one read");
        assertEquals(300, reader.unpackLong());
        reader.release();
    }

    @Test
    public void emptyInputHasNoNext() throws IOException
    {
        forEachSource(new byte[0], r -> assertFalse(r.hasNext()));
    }

    @Test
    public void closeClosesStreamOnlyForStreamSources() throws IOException
    {
        int[] closed = {0};
        InputStream in = new ByteArrayInputStream(new byte[] {(byte) 0xc0}) {
            @Override
            public void close() throws IOException
            {
                closed[0]++;
                super.close();
            }
        };
        MessagePackReader streamReader = new MessagePackReader(MessagePackWriterTest.newIOContext(), in, true);
        streamReader.unpackNil();
        streamReader.close();
        assertEquals(1, closed[0]);
        streamReader.release();

        byte[] data = {(byte) 0xc0};
        MessagePackReader arrayReader = new MessagePackReader(data, 0, 1);
        arrayReader.unpackNil();
        arrayReader.close();
        arrayReader.release();
    }
}
