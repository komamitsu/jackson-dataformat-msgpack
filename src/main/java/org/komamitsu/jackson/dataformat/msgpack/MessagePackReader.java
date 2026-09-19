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

import org.komamitsu.jackson.dataformat.msgpack.MessageFormat.Code;
import tools.jackson.core.io.IOContext;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Decodes MessagePack values from an {@link InputStream} through a buffer borrowed from
 * Jackson's {@link IOContext}, or directly from a caller-supplied byte array with no copy.
 * Running out of input in the middle of a value raises {@link EOFException}.
 */
final class MessagePackReader
{
    private final IOContext ioContext;
    private final InputStream in;
    private final boolean readAhead;
    private final boolean recyclable;
    private byte[] buf;
    private int pos;
    private int end;
    // Bytes consumed and then discarded from the buffer, so that consumed + pos is the
    // total consumed. Starts negative for array sources that begin at an offset.
    private long consumed;

    /**
     * @param readAhead whether the stream may be read past the current value. Pass false
     *                  when the caller keeps using the stream after this reader is done,
     *                  so that it is left positioned at the next value.
     */
    MessagePackReader(IOContext ioContext, InputStream in, boolean readAhead)
    {
        this.ioContext = ioContext;
        this.in = in;
        this.readAhead = readAhead;
        this.recyclable = true;
        this.buf = ioContext.allocReadIOBuffer();
    }

    MessagePackReader(byte[] data, int offset, int length)
    {
        this.ioContext = null;
        this.in = null;
        this.readAhead = false;
        this.recyclable = false;
        this.buf = data;
        this.pos = offset;
        this.end = offset + length;
        this.consumed = -offset;
    }

    long getTotalReadBytes()
    {
        return consumed + pos;
    }

    boolean hasNext() throws IOException
    {
        if (pos < end) {
            return true;
        }
        return in != null && fillAtLeast(1);
    }

    MessageFormat getNextFormat() throws IOException
    {
        ensure(1);
        return MessageFormat.valueOf(buf[pos]);
    }

    void unpackNil() throws IOException
    {
        expect(Code.NIL, "nil");
    }

    boolean unpackBoolean() throws IOException
    {
        byte b = readByte();
        if (b == Code.TRUE) {
            return true;
        }
        if (b == Code.FALSE) {
            return false;
        }
        throw unexpected(b, "boolean");
    }

    long unpackLong() throws IOException
    {
        byte b = readByte();
        if (isFixInt(b)) {
            return b;
        }
        switch (b) {
            case Code.UINT8:
                return readByte() & 0xff;
            case Code.UINT16:
                return readShort() & 0xffff;
            case Code.UINT32:
                return readInt() & 0xffffffffL;
            case Code.UINT64:
                long u64 = readLong();
                if (u64 < 0) {
                    throw new IOException("Unsigned 64-bit integer " + Long.toUnsignedString(u64) + " does not fit in a long");
                }
                return u64;
            case Code.INT8:
                return readByte();
            case Code.INT16:
                return readShort();
            case Code.INT32:
                return readInt();
            case Code.INT64:
                return readLong();
            default:
                throw unexpected(b, "integer");
        }
    }

    BigInteger unpackBigInteger() throws IOException
    {
        ensure(1);
        if (buf[pos] == Code.UINT64) {
            pos++;
            long u64 = readLong();
            if (u64 < 0) {
                return BigInteger.valueOf(u64 & Long.MAX_VALUE).setBit(63);
            }
            return BigInteger.valueOf(u64);
        }
        return BigInteger.valueOf(unpackLong());
    }

    double unpackDouble() throws IOException
    {
        byte b = readByte();
        if (b == Code.FLOAT32) {
            return Float.intBitsToFloat(readInt());
        }
        if (b == Code.FLOAT64) {
            return Double.longBitsToDouble(readLong());
        }
        throw unexpected(b, "float");
    }

    String unpackString() throws IOException
    {
        int len = unpackRawStringHeader();
        if (len <= buf.length) {
            ensure(len);
            String s = new String(buf, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }
        return new String(readPayload(len), StandardCharsets.UTF_8);
    }

    int unpackRawStringHeader() throws IOException
    {
        byte b = readByte();
        if ((b & 0xe0) == (Code.FIXSTR_PREFIX & 0xff)) {
            return b & 0x1f;
        }
        switch (b) {
            case Code.STR8:
                return readByte() & 0xff;
            case Code.STR16:
                return readShort() & 0xffff;
            case Code.STR32:
                return readLength(readInt(), "string");
            default:
                throw unexpected(b, "string");
        }
    }

    int unpackBinaryHeader() throws IOException
    {
        byte b = readByte();
        switch (b) {
            case Code.BIN8:
                return readByte() & 0xff;
            case Code.BIN16:
                return readShort() & 0xffff;
            case Code.BIN32:
                return readLength(readInt(), "binary");
            default:
                throw unexpected(b, "binary");
        }
    }

    int unpackArrayHeader() throws IOException
    {
        byte b = readByte();
        if ((b & 0xf0) == (Code.FIXARRAY_PREFIX & 0xff)) {
            return b & 0x0f;
        }
        switch (b) {
            case Code.ARRAY16:
                return readShort() & 0xffff;
            case Code.ARRAY32:
                return readLength(readInt(), "array");
            default:
                throw unexpected(b, "array");
        }
    }

    int unpackMapHeader() throws IOException
    {
        byte b = readByte();
        if ((b & 0xf0) == (Code.FIXMAP_PREFIX & 0xff)) {
            return b & 0x0f;
        }
        switch (b) {
            case Code.MAP16:
                return readShort() & 0xffff;
            case Code.MAP32:
                return readLength(readInt(), "map");
            default:
                throw unexpected(b, "map");
        }
    }

    ExtensionTypeHeader unpackExtensionTypeHeader() throws IOException
    {
        byte b = readByte();
        switch (b) {
            case Code.FIXEXT1:
                return new ExtensionTypeHeader(readByte(), 1);
            case Code.FIXEXT2:
                return new ExtensionTypeHeader(readByte(), 2);
            case Code.FIXEXT4:
                return new ExtensionTypeHeader(readByte(), 4);
            case Code.FIXEXT8:
                return new ExtensionTypeHeader(readByte(), 8);
            case Code.FIXEXT16:
                return new ExtensionTypeHeader(readByte(), 16);
            case Code.EXT8: {
                int len = readByte() & 0xff;
                return new ExtensionTypeHeader(readByte(), len);
            }
            case Code.EXT16: {
                int len = readShort() & 0xffff;
                return new ExtensionTypeHeader(readByte(), len);
            }
            case Code.EXT32: {
                int len = readLength(readInt(), "extension");
                return new ExtensionTypeHeader(readByte(), len);
            }
            default:
                throw unexpected(b, "extension");
        }
    }

    Instant unpackTimestamp(ExtensionTypeHeader header) throws IOException
    {
        if (header.getType() != Code.EXT_TIMESTAMP) {
            throw new IOException("Expected extension type -1 for a timestamp but got " + header.getType());
        }
        switch (header.getLength()) {
            case 4:
                return Instant.ofEpochSecond(readInt() & 0xffffffffL);
            case 8: {
                long data64 = readLong();
                return Instant.ofEpochSecond(data64 & 0x3ffffffffL, data64 >>> 34);
            }
            case 12: {
                int nsec = readInt();
                long sec = readLong();
                return Instant.ofEpochSecond(sec, nsec);
            }
            default:
                throw new IOException("Timestamp extension has unexpected length " + header.getLength());
        }
    }

    byte[] readPayload(int len) throws IOException
    {
        byte[] result = new byte[len];
        int copied = Math.min(len, end - pos);
        System.arraycopy(buf, pos, result, 0, copied);
        pos += copied;
        int remaining = len - copied;
        if (remaining == 0) {
            return result;
        }
        if (in == null) {
            throw eof();
        }
        // The buffer is drained; read the rest straight into the result.
        consumed += pos;
        pos = 0;
        end = 0;
        int off = copied;
        while (remaining > 0) {
            int n = in.read(result, off, remaining);
            if (n < 0) {
                throw eof();
            }
            off += n;
            remaining -= n;
        }
        consumed += len - copied;
        return result;
    }

    void close() throws IOException
    {
        if (in != null) {
            in.close();
        }
    }

    void release()
    {
        byte[] b = buf;
        if (recyclable && b != null) {
            buf = null;
            ioContext.releaseReadIOBuffer(b);
        }
    }

    private static boolean isFixInt(byte b)
    {
        return b >= 0 || (b & 0xe0) == (Code.NEGFIXINT_PREFIX & 0xff);
    }

    private void expect(byte code, String what) throws IOException
    {
        byte b = readByte();
        if (b != code) {
            throw unexpected(b, what);
        }
    }

    private static IOException unexpected(byte b, String expected)
    {
        return new IOException(String.format("Expected %s but got format byte 0x%02x", expected, b & 0xff));
    }

    private static int readLength(int len, String what) throws IOException
    {
        if (len < 0) {
            throw new IOException(what + " length " + Integer.toUnsignedString(len) + " exceeds the supported maximum");
        }
        return len;
    }

    private static EOFException eof()
    {
        return new EOFException("Unexpected end of MessagePack input");
    }

    private byte readByte() throws IOException
    {
        ensure(1);
        return buf[pos++];
    }

    private short readShort() throws IOException
    {
        ensure(2);
        short v = (short) (((buf[pos] & 0xff) << 8) | (buf[pos + 1] & 0xff));
        pos += 2;
        return v;
    }

    private int readInt() throws IOException
    {
        ensure(4);
        int v = ((buf[pos] & 0xff) << 24)
                | ((buf[pos + 1] & 0xff) << 16)
                | ((buf[pos + 2] & 0xff) << 8)
                | (buf[pos + 3] & 0xff);
        pos += 4;
        return v;
    }

    private long readLong() throws IOException
    {
        ensure(8);
        long hi = readInt() & 0xffffffffL;
        long lo = readInt() & 0xffffffffL;
        return (hi << 32) | lo;
    }

    // Ensures n bytes are available from pos. Callers only ask for at most the buffer size.
    private void ensure(int n) throws IOException
    {
        if (end - pos >= n) {
            return;
        }
        if (in == null || !fillAtLeast(n)) {
            throw eof();
        }
    }

    // Moves unread bytes to the start of the buffer and reads until n are available.
    // Returns false if the stream ends first.
    private boolean fillAtLeast(int n) throws IOException
    {
        assert n <= buf.length;
        int available = end - pos;
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, available);
            consumed += pos;
            pos = 0;
            end = available;
        }
        while (end < n) {
            int r = in.read(buf, end, readAhead ? buf.length - end : n - end);
            if (r < 0) {
                return false;
            }
            end += r;
        }
        return true;
    }
}
