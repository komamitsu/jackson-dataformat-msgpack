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
 *
 * <p>Container headers carry the element count, which is unknown until the container
 * closes. {@link #openContainer} reserves room for the header and puts the writer in hold
 * mode, where the buffer grows instead of flushing so the header can still be patched by
 * {@link #closeContainer}. Outside hold mode the buffer flushes to the stream as it fills.
 */
final class MessagePackWriter
{
    private static final int NANOS_PER_SECOND = 1_000_000_000;
    private static final int MAX_CONTAINER_HEADER = 5;
    private static final int MAX_STRING_HEADER = 5;

    private final IOContext ioContext;
    private final OutputStream out;
    private final boolean str8FormatSupport;
    private byte[] buf;
    private int pos;
    // Whether buf is the IOContext's, to be returned exactly once.
    private boolean borrowed;
    // Number of open containers. While positive the buffer must not flush.
    private int holdDepth;

    MessagePackWriter(IOContext ioContext, OutputStream out, boolean str8FormatSupport)
    {
        this.ioContext = ioContext;
        this.out = out;
        this.str8FormatSupport = str8FormatSupport;
        this.buf = ioContext.allocWriteEncodingBuffer();
        this.borrowed = true;
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

    /**
     * Whether the value fits a MessagePack integer: int64 or uint64, so -2^63 to 2^64-1.
     */
    static boolean fitsInteger(BigInteger bi)
    {
        return bi.bitLength() <= 63 || (bi.bitLength() == 64 && bi.signum() == 1);
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
            throw new IllegalArgumentException("MessagePack integers range from -2^63 to 2^64-1, got " + bi);
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
        int charLen = s.length();
        // Worst case: 3 bytes per char plus the largest header.
        int worstCase = 3 * charLen + MAX_STRING_HEADER;
        if (worstCase <= buf.length) {
            ensure(worstCase);
            packStringInPlace(s, charLen);
            return;
        }
        // Too long to encode into the buffer in one go: count first, then write.
        int byteLen = utf8Length(s);
        packRawStringHeader(byteLen);
        if (holdDepth > 0) {
            grow(pos + byteLen);
            pos = encodeUtf8(s, buf, pos);
        }
        else {
            writePayload(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    // Encodes in a single pass, without counting the bytes first. A char is at least one
    // byte, so the char count gives the smallest header the string can need. That many bytes
    // are left free, the string is encoded after them, and only if non-ASCII text pushed the
    // byte length over the next header boundary is the payload moved to make room.
    private void packStringInPlace(String s, int charLen)
    {
        int reserved = stringHeaderLength(charLen);
        int start = pos + reserved;
        int end = encodeUtf8(s, buf, start);
        int byteLen = end - start;
        int needed = stringHeaderLength(byteLen);
        if (needed != reserved) {
            System.arraycopy(buf, start, buf, pos + needed, byteLen);
        }
        putStringHeader(buf, pos, byteLen);
        pos += needed + byteLen;
    }

    private int stringHeaderLength(int len)
    {
        if (len < (1 << 5)) {
            return 1;
        }
        if (str8FormatSupport && len < (1 << 8)) {
            return 2;
        }
        if (len < (1 << 16)) {
            return 3;
        }
        return 5;
    }

    private void putStringHeader(byte[] b, int off, int len)
    {
        if (len < (1 << 5)) {
            b[off] = (byte) (Code.FIXSTR_PREFIX | len);
        }
        else if (str8FormatSupport && len < (1 << 8)) {
            b[off] = Code.STR8;
            b[off + 1] = (byte) len;
        }
        else if (len < (1 << 16)) {
            b[off] = Code.STR16;
            b[off + 1] = (byte) (len >> 8);
            b[off + 2] = (byte) len;
        }
        else {
            b[off] = Code.STR32;
            b[off + 1] = (byte) (len >> 24);
            b[off + 2] = (byte) (len >> 16);
            b[off + 3] = (byte) (len >> 8);
            b[off + 4] = (byte) len;
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
        ensure(MAX_CONTAINER_HEADER);
        pos += putContainerHeader(buf, pos, false, size);
    }

    void packMapHeader(int size) throws IOException
    {
        ensure(MAX_CONTAINER_HEADER);
        pos += putContainerHeader(buf, pos, true, size);
    }

    /**
     * Starts a container whose element count may not be known yet. Returns the offset of its
     * header; the caller must keep it, along with {@link #position()} minus it as the reserved
     * header length, and pass both to {@link #closeContainer}. With a non-negative size hint the
     * final header is written now, and closing only patches it if the hint turns out wrong.
     */
    int openContainer(boolean map, int sizeHint) throws IOException
    {
        ensure(MAX_CONTAINER_HEADER);
        int offset = pos;
        holdDepth++;
        if (sizeHint < 0) {
            pos++;
        }
        else {
            pos += putContainerHeader(buf, pos, map, sizeHint);
        }
        return offset;
    }

    /**
     * Writes the final header for a container opened with {@link #openContainer}, moving the
     * children if the header needs a different length than was reserved.
     */
    void closeContainer(boolean map, int offset, int reserved, int count) throws IOException
    {
        int needed = containerHeaderLength(count);
        int shift = needed - reserved;
        if (shift != 0) {
            int childStart = offset + reserved;
            int childLength = pos - childStart;
            if (shift > 0) {
                ensure(shift);
            }
            System.arraycopy(buf, childStart, buf, childStart + shift, childLength);
            pos += shift;
        }
        putContainerHeader(buf, offset, map, count);
        holdDepth--;
    }

    int position()
    {
        return pos;
    }

    /**
     * Bytes encoded but not yet written to the stream.
     */
    int pending()
    {
        return pos;
    }

    /**
     * Drops everything encoded from the given offset on and leaves hold mode. Used to
     * abandon an unfinished container while keeping complete values written before it.
     */
    void discardFrom(int offset)
    {
        pos = offset;
        holdDepth = 0;
    }

    private static int containerHeaderLength(int count)
    {
        if (count < (1 << 4)) {
            return 1;
        }
        if (count < (1 << 16)) {
            return 3;
        }
        return 5;
    }

    private static int putContainerHeader(byte[] b, int off, boolean map, int count)
    {
        if (count < (1 << 4)) {
            b[off] = (byte) ((map ? Code.FIXMAP_PREFIX : Code.FIXARRAY_PREFIX) | count);
            return 1;
        }
        if (count < (1 << 16)) {
            b[off] = map ? Code.MAP16 : Code.ARRAY16;
            b[off + 1] = (byte) (count >>> 8);
            b[off + 2] = (byte) count;
            return 3;
        }
        b[off] = map ? Code.MAP32 : Code.ARRAY32;
        putInt(b, off + 1, count);
        return 5;
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
        byte[] payload = timestampPayload(epochSecond, nanoAdjustment);
        packExtensionTypeHeader(Code.EXT_TIMESTAMP, payload.length);
        writePayload(payload);
    }

    /**
     * Encodes a timestamp as the payload of an extension value, without the extension header.
     */
    static byte[] timestampPayload(Instant instant)
    {
        return timestampPayload(instant.getEpochSecond(), instant.getNano());
    }

    private static byte[] timestampPayload(long epochSecond, int nanoAdjustment)
    {
        long sec = Math.addExact(epochSecond, Math.floorDiv(nanoAdjustment, NANOS_PER_SECOND));
        long nsec = Math.floorMod((long) nanoAdjustment, NANOS_PER_SECOND);
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
        if (holdDepth > 0) {
            grow(pos + len);
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

    void flush() throws IOException
    {
        if (holdDepth > 0) {
            throw new IllegalStateException("Cannot flush while " + holdDepth + " container(s) are open");
        }
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
        buf = null;
        if (b != null && borrowed) {
            borrowed = false;
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
        if (buf.length - pos >= n) {
            return;
        }
        if (holdDepth == 0) {
            flushBuffer();
            if (buf.length >= n) {
                return;
            }
        }
        grow(pos + n);
    }

    private void grow(int minCapacity)
    {
        byte[] old = buf;
        byte[] bigger = new byte[Math.max(old.length * 2, minCapacity)];
        System.arraycopy(old, 0, bigger, 0, pos);
        buf = bigger;
        if (borrowed) {
            borrowed = false;
            ioContext.releaseWriteEncodingBuffer(old);
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
