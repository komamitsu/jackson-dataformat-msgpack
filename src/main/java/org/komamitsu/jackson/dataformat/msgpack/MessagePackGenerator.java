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

import tools.jackson.core.Base64Variant;
import tools.jackson.core.JacksonException;
import tools.jackson.core.util.JacksonFeatureSet;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.SerializableString;
import tools.jackson.core.StreamWriteCapability;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.DupDetector;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.base.GeneratorBase;
import tools.jackson.core.io.IOContext;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Encodes each write call straight into the {@link MessagePackWriter}. Container headers
 * carry the element count, so a container's header is reserved when it opens and patched
 * when it closes; see {@link MessagePackWriter#openContainer}.
 */
public class MessagePackGenerator
        extends GeneratorBase
{
    private final MessagePackWriter writer;
    // False for a nested generator writing a complex key into its parent's writer.
    private final boolean ownsWriter;
    private final OutputStream output;
    private final boolean str8FormatSupport;
    private final boolean supportIntegerKeys;
    private MessagePackWriteContext writeContext;

    public MessagePackGenerator(
            ObjectWriteContext writeCtxt,
            IOContext ioCtxt,
            int streamWriteFeatures,
            OutputStream out,
            boolean str8FormatSupport,
            boolean supportIntegerKeys)
    {
        this(writeCtxt, ioCtxt, streamWriteFeatures, out,
                new MessagePackWriter(ioCtxt, out, str8FormatSupport), true, 0, str8FormatSupport, supportIntegerKeys);
    }

    private MessagePackGenerator(
            ObjectWriteContext writeCtxt,
            IOContext ioCtxt,
            int streamWriteFeatures,
            OutputStream out,
            MessagePackWriter writer,
            boolean ownsWriter,
            int nestingDepth,
            boolean str8FormatSupport,
            boolean supportIntegerKeys)
    {
        super(writeCtxt, ioCtxt, streamWriteFeatures);
        this.output = out;
        this.writer = writer;
        this.ownsWriter = ownsWriter;
        this.str8FormatSupport = str8FormatSupport;
        this.supportIntegerKeys = supportIntegerKeys;
        this.writeContext = MessagePackWriteContext.createRootContext(
                StreamWriteFeature.STRICT_DUPLICATE_DETECTION.enabledIn(streamWriteFeatures)
                        ? DupDetector.rootDetector(this) : null,
                nestingDepth);
    }

    @Override
    public JsonGenerator writeStartArray() throws JacksonException
    {
        return writeStartArray(null);
    }

    @Override
    public JsonGenerator writeStartArray(Object currentValue) throws JacksonException
    {
        return writeStartArray(currentValue, -1);
    }

    // The size hint is used when given, but not trusted: Jackson passes -1 for known-size
    // collections when dynamic filters (Views, @JsonFilter) are involved, and a caller could
    // pass a wrong value. closeContainer patches the header from the actual count either way.
    @Override
    public JsonGenerator writeStartArray(Object currentValue, int size) throws JacksonException
    {
        // Checked before anything is counted or created, so a rejected container leaves no trace.
        streamWriteConstraints().validateNestingDepth(writeContext.getNestingDepth() + 1);
        _verifyValueWrite("start an array");
        writeContext = writeContext.createChildArrayContext(currentValue);
        openContainer(false, size);
        return this;
    }

    @Override
    public JsonGenerator writeEndArray() throws JacksonException
    {
        if (!writeContext.inArray()) {
            _reportError("Current context not an array but " + writeContext.typeDesc());
        }
        closeContainer(false);
        return this;
    }

    @Override
    public JsonGenerator writeStartObject() throws JacksonException
    {
        return writeStartObject(null);
    }

    @Override
    public JsonGenerator writeStartObject(Object currentValue) throws JacksonException
    {
        return writeStartObject(currentValue, -1);
    }

    @Override
    public JsonGenerator writeStartObject(Object forValue, int size) throws JacksonException
    {
        // Checked before anything is counted or created, so a rejected container leaves no trace.
        streamWriteConstraints().validateNestingDepth(writeContext.getNestingDepth() + 1);
        _verifyValueWrite("start an object");
        writeContext = writeContext.createChildObjectContext(forValue);
        openContainer(true, size);
        return this;
    }

    @Override
    public JsonGenerator writeEndObject() throws JacksonException
    {
        if (!writeContext.inObject()) {
            _reportError("Current context not an object but " + writeContext.typeDesc());
        }
        if (writeContext.isExpectingValue()) {
            _reportError("Cannot close Object, property name written but no value");
        }
        closeContainer(true);
        return this;
    }

    private void openContainer(boolean map, int sizeHint)
    {
        pack(w -> {
            int offset = w.openContainer(map, sizeHint);
            writeContext.setHeader(offset, w.position() - offset);
        });
    }

    private void closeContainer(boolean map)
    {
        pack(w -> w.closeContainer(map, writeContext.headerOffset(), writeContext.reservedHeaderLength(),
                writeContext.getEntryCount()));
        writeContext = writeContext.getParent();
    }

    // Rejects a key the format cannot encode before the context records the name, so a caller
    // that catches the failure is not left with a name whose bytes were never written.
    private static void checkKeyRepresentable(Object key)
    {
        if (key instanceof BigInteger && !MessagePackWriter.fitsInteger((BigInteger) key)) {
            throw new IllegalArgumentException("MessagePack integers range from -2^63 to 2^64-1, got " + key);
        }
        if (key instanceof BigDecimal) {
            representable((BigDecimal) key);
        }
    }

    private void packKey(Object key) throws IOException
    {
        if (key instanceof String) {
            writer.packString((String) key);
        }
        else if (key instanceof Integer) {
            writer.packInt((Integer) key);
        }
        else if (key == null) {
            writer.packNil();
        }
        else if (key instanceof Long) {
            writer.packLong((Long) key);
        }
        else if (key instanceof Float) {
            writer.packFloat((Float) key);
        }
        else if (key instanceof Double) {
            writer.packDouble((Double) key);
        }
        else if (key instanceof BigInteger) {
            writer.packBigInteger((BigInteger) key);
        }
        else if (key instanceof BigDecimal) {
            packBigDecimal((BigDecimal) key);
        }
        else if (key instanceof Boolean) {
            writer.packBoolean((Boolean) key);
        }
        else if (key instanceof ByteBuffer) {
            packByteBuffer((ByteBuffer) key);
        }
        else if (key instanceof MessagePackExtensionType) {
            packExtensionType((MessagePackExtensionType) key);
        }
        else {
            // Any other key type is serialized as a nested value in key position, straight into
            // this generator's writer. The nested generator only tracks its own context stack,
            // but starts counting depth where this one is so the nesting limit still holds.
            try (MessagePackGenerator nested = new MessagePackGenerator(
                    objectWriteContext(), _ioContext, _streamWriteFeatures, output,
                    writer, false, writeContext.getNestingDepth(), str8FormatSupport, supportIntegerKeys)) {
                objectWriteContext().writeValue(nested, key);
            }
        }
    }

    private void packByteBuffer(ByteBuffer bb) throws IOException
    {
        int len = bb.remaining();
        if (bb.hasArray() && !bb.isReadOnly()) {
            writer.packBinaryHeader(len);
            writer.writePayload(bb.array(), bb.arrayOffset() + bb.position(), len);
        }
        else {
            byte[] data = new byte[len];
            bb.duplicate().get(data);
            writer.packBinaryHeader(len);
            writer.writePayload(data);
        }
    }

    private void packExtensionType(MessagePackExtensionType extensionType) throws IOException
    {
        byte[] extData = extensionType.getData();
        writer.packExtensionTypeHeader(extensionType.getType(), extData.length);
        writer.writePayload(extData);
    }

    private void packBigDecimal(BigDecimal decimal) throws IOException
    {
        packDecimalEncoding(representable(decimal));
    }

    // How a BigDecimal goes on the wire: as an integer if it has no fraction and fits one,
    // else as a double if that is exact. Decided before anything is written or counted, so
    // an unrepresentable value leaves no trace.
    private static Number representable(BigDecimal decimal)
    {
        try {
            BigInteger integer = decimal.toBigIntegerExact();
            if (MessagePackWriter.fitsInteger(integer)) {
                return integer;
            }
        }
        catch (ArithmeticException notIntegral) {
            // Falls through to the double check.
        }
        double doubleValue = decimal.doubleValue();
        if (Double.isInfinite(doubleValue) || decimal.compareTo(BigDecimal.valueOf(doubleValue)) != 0) {
            throw new IllegalArgumentException("MessagePack cannot serialize a BigDecimal that can't be represented as double. " + decimal);
        }
        return doubleValue;
    }

    // Takes what representable() decided, so the cast holds by construction.
    private void packDecimalEncoding(Number encoding) throws IOException
    {
        if (encoding instanceof BigInteger) {
            writer.packBigInteger((BigInteger) encoding);
        }
        else {
            writer.packDouble((Double) encoding);
        }
    }

    @FunctionalInterface
    private interface ValueWriter
    {
        void write(MessagePackWriter writer) throws IOException;
    }

    // Every scalar write is the same three steps: check the value is allowed here, encode it,
    // and turn a stream failure into the exception type Jackson expects.
    private JsonGenerator writeValue(ValueWriter value)
    {
        verifyValueWrite();
        pack(value);
        return this;
    }

    // Encodes without the value check, for keys and container headers, which have their own.
    private void pack(ValueWriter value)
    {
        try {
            value.write(writer);
        }
        catch (IOException e) {
            throw _wrapIOFailure(e);
        }
    }

    private void verifyValueWrite()
    {
        _verifyValueWrite("write a value");
    }

    // Every write goes through here or through a name write. After close() the writer has
    // given up its buffer, so a write must fail with a clear error rather than an NPE.
    private void checkNotClosed() throws JacksonException
    {
        if (_closed) {
            _reportError("Generator is closed");
        }
    }

    @Override
    public JsonGenerator writePropertyId(long id) throws JacksonException
    {
        checkNotClosed();
        if (this.supportIntegerKeys) {
            if (!writeContext.acceptsName()) {
                _reportError("Can not write a property id, expecting a value");
            }
            writeContext.setName(String.valueOf(id));
            pack(w -> w.packLong(id));
        }
        else {
            writeName(String.valueOf(id));
        }
        return this;
    }

    @Override
    public JacksonFeatureSet<StreamWriteCapability> streamWriteCapabilities()
    {
        return DEFAULT_BINARY_WRITE_CAPABILITIES;
    }

    @Override
    public JsonGenerator writeName(String name) throws JacksonException
    {
        checkNotClosed();
        if (!writeContext.acceptsName()) {
            _reportError("Can not write a property name, expecting a value");
        }
        writeContext.setName(name);
        pack(w -> w.packString(name));
        return this;
    }

    @Override
    public JsonGenerator writeName(SerializableString name) throws JacksonException
    {
        checkNotClosed();
        if (name instanceof MessagePackSerializedString) {
            if (!writeContext.acceptsName()) {
                _reportError("Can not write a property name, expecting a value");
            }
            Object raw = ((MessagePackSerializedString) name).getRawValue();
            checkKeyRepresentable(raw);
            writeContext.setName(name.getValue());
            pack(w -> packKey(raw));
        }
        else {
            writeName(name.getValue());
        }
        return this;
    }

    @Override
    public JsonGenerator writeString(String text) throws JacksonException
    {
        if (text == null) {
            return writeNull();
        }
        return writeValue(w -> w.packString(text));
    }

    @Override
    public JsonGenerator writeString(char[] text, int offset, int len) throws JacksonException
    {
        return writeString(new String(text, offset, len));
    }

    @Override
    public JsonGenerator writeString(Reader reader, int len) throws JacksonException
    {
        try {
            long remaining = len < 0 ? Long.MAX_VALUE : len;
            // Cap chunk size: len is a caller hint and can be arbitrarily large.
            // Pre-allocating new StringBuilder(len) would reserve len*2 bytes upfront,
            // which is an OOM risk for large inputs. The StringBuilder grows as needed.
            int chunkSize = (int) Math.min(remaining, 8192);
            StringBuilder sb = new StringBuilder(chunkSize);
            char[] tmpBuf = new char[chunkSize];
            while (remaining > 0) {
                int read = reader.read(tmpBuf, 0, (int) Math.min(remaining, tmpBuf.length));
                if (read < 0) {
                    break;
                }
                sb.append(tmpBuf, 0, read);
                remaining -= read;
            }
            return writeString(sb.toString());
        }
        catch (IOException e) {
            throw _wrapIOFailure(e);
        }
    }

    @Override
    public JsonGenerator writeString(SerializableString text) throws JacksonException
    {
        return writeString(text.getValue());
    }

    @Override
    public JsonGenerator writeRawUTF8String(byte[] text, int offset, int length) throws JacksonException
    {
        return writeUTF8String(text, offset, length);
    }

    @Override
    public JsonGenerator writeUTF8String(byte[] text, int offset, int length) throws JacksonException
    {
        // Checked before the header is written or the value counted, so a bad slice leaves
        // the stream as it was.
        Objects.checkFromIndexSize(offset, length, text.length);
        return writeValue(w -> {
            w.packRawStringHeader(length);
            w.writePayload(text, offset, length);
        });
    }

    @Override
    public JsonGenerator writeRaw(String text) throws JacksonException
    {
        return writeString(text);
    }

    @Override
    public JsonGenerator writeRaw(String text, int offset, int len) throws JacksonException
    {
        return writeString(text.substring(offset, offset + len));
    }

    @Override
    public JsonGenerator writeRaw(char[] text, int offset, int len) throws JacksonException
    {
        return writeString(new String(text, offset, len));
    }

    @Override
    public JsonGenerator writeRaw(char c) throws JacksonException
    {
        return writeString(String.valueOf(c));
    }

    @Override
    public JsonGenerator writeBinary(Base64Variant b64variant, byte[] data, int offset, int len) throws JacksonException
    {
        Objects.checkFromIndexSize(offset, len, data.length);
        return writeValue(w -> {
            w.packBinaryHeader(len);
            w.writePayload(data, offset, len);
        });
    }

    @Override
    public JsonGenerator writeNumber(short v) throws JacksonException
    {
        return writeNumber((int) v);
    }

    @Override
    public JsonGenerator writeNumber(int v) throws JacksonException
    {
        return writeValue(w -> w.packInt(v));
    }

    @Override
    public JsonGenerator writeNumber(long v) throws JacksonException
    {
        return writeValue(w -> w.packLong(v));
    }

    @Override
    public JsonGenerator writeNumber(BigInteger v) throws JacksonException
    {
        if (v == null) {
            return writeNull();
        }
        // Rejected before the value is counted, so the stream stays consistent.
        if (!MessagePackWriter.fitsInteger(v)) {
            throw new IllegalArgumentException("MessagePack integers range from -2^63 to 2^64-1, got " + v);
        }
        return writeValue(w -> w.packBigInteger(v));
    }

    @Override
    public JsonGenerator writeNumber(double d) throws JacksonException
    {
        return writeValue(w -> w.packDouble(d));
    }

    @Override
    public JsonGenerator writeNumber(float f) throws JacksonException
    {
        return writeValue(w -> w.packFloat(f));
    }

    @Override
    public JsonGenerator writeNumber(BigDecimal dec) throws JacksonException
    {
        if (dec == null) {
            return writeNull();
        }
        Number encoding = representable(dec);
        return writeValue(w -> packDecimalEncoding(encoding));
    }

    @Override
    public JsonGenerator writeNumber(String encodedValue) throws JacksonException
    {
        // There is a room to improve this API's performance while the implementation is robust.
        // If users can use other MessagePackGenerator#writeNumber APIs that accept
        // proper numeric types not String, it's better to use the other APIs instead.
        if (encodedValue == null) {
            return writeNull();
        }
        try {
            return writeNumber(Long.parseLong(encodedValue));
        }
        catch (NumberFormatException ignored) {
        }

        try {
            return writeNumber(new BigInteger(encodedValue));
        }
        catch (NumberFormatException ignored) {
        }

        try {
            BigDecimal bd = new BigDecimal(encodedValue);
            double d = bd.doubleValue();

            // Check if the double can perfectly represent the exact decimal value.
            // isInfinite guard: values like "1e309" overflow double to Infinity; keep as BigDecimal.
            if (!Double.isInfinite(d) && bd.compareTo(new BigDecimal(String.valueOf(d))) == 0) {
                // It's a safe ordinary floating-point number.
                return writeNumber(d);
            }
            // It has more precision than a double can handle, or overflows double range.
            return writeNumber(bd);
        }
        catch (NumberFormatException e) {
            // Fall back for NaN, Infinity, -Infinity which BigDecimal rejects.
            try {
                return writeNumber(Double.parseDouble(encodedValue));
            }
            catch (NumberFormatException ignored) {
            }
        }

        throw new NumberFormatException(encodedValue);
    }

    @Override
    public JsonGenerator writeBoolean(boolean state) throws JacksonException
    {
        return writeValue(w -> w.packBoolean(state));
    }

    @Override
    public JsonGenerator writeNull() throws JacksonException
    {
        return writeValue(w -> w.packNil());
    }

    public void writeExtensionType(MessagePackExtensionType extensionType)
    {
        verifyValueWrite();
        pack(w -> packExtensionType(extensionType));
    }

    @Override
    public void close() throws JacksonException
    {
        if (_closed) {
            return;
        }
        try {
            if (writeContext.inRoot()) {
                flush();
            }
            else if (StreamWriteFeature.AUTO_CLOSE_CONTENT.enabledIn(_streamWriteFeatures)) {
                // Same as Jackson's own generators: finish whatever is open, then write it out.
                while (!writeContext.inRoot()) {
                    if (writeContext.inArray()) {
                        writeEndArray();
                    }
                    else {
                        // A name whose value never came (typically a serializer that threw)
                        // gets nil, keeping the output valid MessagePack.
                        if (writeContext.isExpectingValue()) {
                            writeNull();
                        }
                        writeEndObject();
                    }
                }
                flush();
            }
            else {
                // A partly written value cannot be completed, so none of it reaches the stream.
                // Complete values written before it still do.
                MessagePackWriteContext outermost = writeContext;
                while (!outermost.getParent().inRoot()) {
                    outermost = outermost.getParent();
                }
                writer.discardFrom(outermost.headerOffset());
                writeContext = outermost.getParent();
                flush();
            }
        }
        finally {
            super.close();
        }
    }

    @Override
    public void flush() throws JacksonException
    {
        if (!ownsWriter || !writeContext.inRoot()) {
            // Headers of open containers are still to be patched, so nothing can be written yet.
            return;
        }
        pack(MessagePackWriter::flush);
    }

    @Override
    public tools.jackson.core.Version version()
    {
        return PackageVersion.VERSION;
    }

    @Override
    public TokenStreamContext streamWriteContext()
    {
        return writeContext;
    }

    @Override
    public Object streamWriteOutputTarget()
    {
        return output;
    }

    @Override
    public int streamWriteOutputBuffered()
    {
        return writer.pending();
    }

    @Override
    public Object currentValue()
    {
        return writeContext.currentValue();
    }

    @Override
    public void assignCurrentValue(Object v)
    {
        writeContext.assignCurrentValue(v);
    }

    @Override
    protected void _closeInput() throws IOException
    {
        if (ownsWriter && StreamWriteFeature.AUTO_CLOSE_TARGET.enabledIn(_streamWriteFeatures)) {
            writer.close();
        }
    }

    @Override
    protected void _releaseBuffers()
    {
        if (ownsWriter) {
            writer.release();
        }
    }

    @Override
    protected void _verifyValueWrite(String typeMsg) throws JacksonException
    {
        checkNotClosed();
        if (!writeContext.acceptsValue()) {
            _reportError("Cannot " + typeMsg + ", expecting a property name");
        }
        writeContext.countValue();
    }
}
