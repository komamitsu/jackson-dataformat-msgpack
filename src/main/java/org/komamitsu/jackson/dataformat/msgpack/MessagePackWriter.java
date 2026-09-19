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

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Encodes MessagePack values into a buffer borrowed from Jackson's {@link IOContext} and
 * flushes it to an {@link OutputStream}. Format selection matches msgpack-core's
 * {@code MessagePacker} byte for byte.
 */
final class MessagePackWriter
{
    private static final int NANOS_PER_SECOND = 1_000_000_000;
    // Largest fixed-size write: EXT8 header (3) plus a timestamp96 payload (12).
    private static final int MAX_FIXED_WRITE = 15;
    private static final int STANDALONE_BUFFER_SIZE = 2000;

    private final IOContext ioContext;
    private final OutputStream out;
    private final boolean str8FormatSupport;
    private byte[] buf;
    private int pos;

    MessagePackWriter(IOContext ioContext, OutputStream out, boolean str8FormatSupport)
    {
        this.ioContext = ioContext;
        this.out = out;
        this.str8FormatSupport = str8FormatSupport;
        this.buf = ioContext.allocWriteEncodingBuffer();
    }

    // For nested generators, whose IOContext already has its write buffer checked out by the
    // enclosing generator. An IOContext refuses to hand out the same buffer twice.
    MessagePackWriter(OutputStream out, boolean str8FormatSupport)
    {
        this.ioContext = null;
        this.out = out;
        this.str8FormatSupport = str8FormatSupport;
        this.buf = new byte[STANDALONE_BUFFER_SIZE];
    }

    void packNil() throws IOException
    {
        writeByte(Code.NIL);
    }

    void packBoolean(boolean b) throws IOException
    {
        writeByte(b ? Code.TRUE : Code.FALSE);
    }

    void packInt(int v) throws IOException
    {
        packLong(v);
    }

    void packLong(long v) throws IOException
    {
        if (v < -(1L << 5)) {
            if (v < -(1L << 15)) {
                if (v < -(1L << 31)) {
                    writeByteAndLong(Code.INT64, v);
                }
                else {
                    writeByteAndInt(Code.INT32, (int) v);
                }
            }
            else {
                if (v < -(1 << 7)) {
                    writeByteAndShort(Code.INT16, (short) v);
                }
                else {
                    writeByteAndByte(Code.INT8, (byte) v);
                }
            }
        }
        else if (v < (1 << 7)) {
            writeByte((byte) v);
        }
        else {
            if (v < (1L << 16)) {
                if (v < (1 << 8)) {
                    writeByteAndByte(Code.UINT8, (byte) v);
                }
                else {
                    writeByteAndShort(Code.UINT16, (short) v);
                }
            }
            else {
                if (v < (1L << 32)) {
                    writeByteAndInt(Code.UINT32, (int) v);
                }
                else {
                    writeByteAndLong(Code.UINT64, v);
                }
            }
        }
    }

    void packBigInteger(BigInteger bi) throws IOException
    {
        if (bi.bitLength() <= 63) {
            packLong(bi.longValue());
        }
        else if (bi.bitLength() == 64 && bi.signum() == 1) {
            writeByteAndLong(Code.UINT64, bi.longValue());
        }
        else {
            throw new IllegalArgumentException("MessagePack cannot serialize BigInteger larger than 2^64-1");
        }
    }

    void packFloat(float v) throws IOException
    {
        writeByteAndInt(Code.FLOAT32, Float.floatToRawIntBits(v));
    }

    void packDouble(double v) throws IOException
    {
        writeByteAndLong(Code.FLOAT64, Double.doubleToRawLongBits(v));
    }

    void packString(String s) throws IOException
    {
        int byteLen = utf8Length(s);
        packRawStringHeader(byteLen);
        if (byteLen <= buf.length - pos) {
            pos = encodeUtf8(s, buf, pos);
        }
        else if (byteLen <= buf.length) {
            flushBuffer();
            pos = encodeUtf8(s, buf, 0);
        }
        else {
            writePayload(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    void packRawStringHeader(int len) throws IOException
    {
        if (len < (1 << 5)) {
            writeByte((byte) (Code.FIXSTR_PREFIX | len));
        }
        else if (str8FormatSupport && len < (1 << 8)) {
            writeByteAndByte(Code.STR8, (byte) len);
        }
        else if (len < (1 << 16)) {
            writeByteAndShort(Code.STR16, (short) len);
        }
        else {
            writeByteAndInt(Code.STR32, len);
        }
    }

    void packBinaryHeader(int len) throws IOException
    {
        if (len < (1 << 8)) {
            writeByteAndByte(Code.BIN8, (byte) len);
        }
        else if (len < (1 << 16)) {
            writeByteAndShort(Code.BIN16, (short) len);
        }
        else {
            writeByteAndInt(Code.BIN32, len);
        }
    }

    void packArrayHeader(int size) throws IOException
    {
        if (size < (1 << 4)) {
            writeByte((byte) (Code.FIXARRAY_PREFIX | size));
        }
        else if (size < (1 << 16)) {
            writeByteAndShort(Code.ARRAY16, (short) size);
        }
        else {
            writeByteAndInt(Code.ARRAY32, size);
        }
    }

    void packMapHeader(int size) throws IOException
    {
        if (size < (1 << 4)) {
            writeByte((byte) (Code.FIXMAP_PREFIX | size));
        }
        else if (size < (1 << 16)) {
            writeByteAndShort(Code.MAP16, (short) size);
        }
        else {
            writeByteAndInt(Code.MAP32, size);
        }
    }

    void packExtensionTypeHeader(byte extType, int payloadLen) throws IOException
    {
        if (payloadLen < (1 << 8)) {
            switch (payloadLen) {
                case 1:
                    writeByteAndByte(Code.FIXEXT1, extType);
                    break;
                case 2:
                    writeByteAndByte(Code.FIXEXT2, extType);
                    break;
                case 4:
                    writeByteAndByte(Code.FIXEXT4, extType);
                    break;
                case 8:
                    writeByteAndByte(Code.FIXEXT8, extType);
                    break;
                case 16:
                    writeByteAndByte(Code.FIXEXT16, extType);
                    break;
                default:
                    writeByteAndByte(Code.EXT8, (byte) payloadLen);
                    writeByte(extType);
                    break;
            }
        }
        else if (payloadLen < (1 << 16)) {
            writeByteAndShort(Code.EXT16, (short) payloadLen);
            writeByte(extType);
        }
        else {
            writeByteAndInt(Code.EXT32, payloadLen);
            writeByte(extType);
        }
    }

    void packTimestamp(Instant instant) throws IOException
    {
        packTimestamp(instant.getEpochSecond(), instant.getNano());
    }

    void packTimestamp(long epochSecond, int nanoAdjustment) throws IOException
    {
        long sec = Math.addExact(epochSecond, Math.floorDiv(nanoAdjustment, NANOS_PER_SECOND));
        long nsec = Math.floorMod((long) nanoAdjustment, NANOS_PER_SECOND);
        ensure(MAX_FIXED_WRITE);
        if (sec >>> 34 == 0) {
            long data64 = (nsec << 34) | sec;
            if ((data64 & 0xffffffff00000000L) == 0L) {
                buf[pos++] = Code.FIXEXT4;
                buf[pos++] = Code.EXT_TIMESTAMP;
                putInt((int) sec);
            }
            else {
                buf[pos++] = Code.FIXEXT8;
                buf[pos++] = Code.EXT_TIMESTAMP;
                putLong(data64);
            }
        }
        else {
            buf[pos++] = Code.EXT8;
            buf[pos++] = (byte) 12;
            buf[pos++] = Code.EXT_TIMESTAMP;
            putInt((int) nsec);
            putLong(sec);
        }
    }

    /**
     * Encodes a timestamp as the payload of an extension value, without the extension header.
     */
    static byte[] timestampPayload(Instant instant)
    {
        long sec = Math.addExact(instant.getEpochSecond(), Math.floorDiv(instant.getNano(), NANOS_PER_SECOND));
        long nsec = Math.floorMod((long) instant.getNano(), NANOS_PER_SECOND);
        byte[] payload;
        if (sec >>> 34 == 0) {
            long data64 = (nsec << 34) | sec;
            if ((data64 & 0xffffffff00000000L) == 0L) {
                payload = new byte[4];
                putInt(payload, 0, (int) sec);
            }
            else {
                payload = new byte[8];
                putLong(payload, 0, data64);
            }
        }
        else {
            payload = new byte[12];
            putInt(payload, 0, (int) nsec);
            putLong(payload, 4, sec);
        }
        return payload;
    }

    void writePayload(byte[] src) throws IOException
    {
        writePayload(src, 0, src.length);
    }

    void writePayload(byte[] src, int off, int len) throws IOException
    {
        if (len <= buf.length - pos) {
            System.arraycopy(src, off, buf, pos, len);
            pos += len;
            return;
        }
        flushBuffer();
        if (len <= buf.length) {
            System.arraycopy(src, off, buf, 0, len);
            pos = len;
        }
        else {
            out.write(src, off, len);
        }
    }

    void addPayload(byte[] src) throws IOException
    {
        writePayload(src, 0, src.length);
    }

    void flush() throws IOException
    {
        flushBuffer();
        out.flush();
    }

    void close() throws IOException
    {
        flush();
        out.close();
    }

    void release()
    {
        byte[] b = buf;
        if (b != null && ioContext != null) {
            buf = null;
            ioContext.releaseWriteEncodingBuffer(b);
        }
    }

    private void flushBuffer() throws IOException
    {
        if (pos > 0) {
            out.write(buf, 0, pos);
            pos = 0;
        }
    }

    private void ensure(int n) throws IOException
    {
        if (buf.length - pos < n) {
            flushBuffer();
        }
    }

    private void writeByte(byte b) throws IOException
    {
        ensure(1);
        buf[pos++] = b;
    }

    private void writeByteAndByte(byte b, byte v) throws IOException
    {
        ensure(2);
        buf[pos++] = b;
        buf[pos++] = v;
    }

    private void writeByteAndShort(byte b, short v) throws IOException
    {
        ensure(3);
        buf[pos++] = b;
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
    }

    private void writeByteAndInt(byte b, int v) throws IOException
    {
        ensure(5);
        buf[pos++] = b;
        putInt(v);
    }

    private void writeByteAndLong(byte b, long v) throws IOException
    {
        ensure(9);
        buf[pos++] = b;
        putLong(v);
    }

    private void putInt(int v)
    {
        putInt(buf, pos, v);
        pos += 4;
    }

    private void putLong(long v)
    {
        putLong(buf, pos, v);
        pos += 8;
    }

    private static void putInt(byte[] b, int off, int v)
    {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void putLong(byte[] b, int off, long v)
    {
        putInt(b, off, (int) (v >>> 32));
        putInt(b, off + 4, (int) v);
    }

    // The two methods below must agree with each other and with String.getBytes(UTF_8),
    // which replaces an unpaired surrogate with a single '?'.

    private static int utf8Length(String s)
    {
        int len = s.length();
        int n = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n++;
            }
            else if (c < 0x800) {
                n += 2;
            }
            else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            }
            else if (Character.isSurrogate(c)) {
                n++;
            }
            else {
                n += 3;
            }
        }
        return n;
    }

    private static int encodeUtf8(String s, byte[] dst, int off)
    {
        int len = s.length();
        int p = off;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                dst[p++] = (byte) c;
            }
            else if (c < 0x800) {
                dst[p++] = (byte) (0xc0 | (c >> 6));
                dst[p++] = (byte) (0x80 | (c & 0x3f));
            }
            else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(++i));
                dst[p++] = (byte) (0xf0 | (cp >> 18));
                dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3f));
                dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3f));
                dst[p++] = (byte) (0x80 | (cp & 0x3f));
            }
            else if (Character.isSurrogate(c)) {
                dst[p++] = (byte) '?';
            }
            else {
                dst[p++] = (byte) (0xe0 | (c >> 12));
                dst[p++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                dst[p++] = (byte) (0x80 | (c & 0x3f));
            }
        }
        return p;
    }
}
