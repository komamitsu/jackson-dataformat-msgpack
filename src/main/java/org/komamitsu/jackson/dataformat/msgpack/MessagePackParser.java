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
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.Version;
import tools.jackson.core.base.ParserMinimalBase;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.json.DupDetector;
import tools.jackson.core.sym.ByteQuadsCanonicalizer;
import org.komamitsu.jackson.dataformat.msgpack.MessageFormat.ValueType;

import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class MessagePackParser
        extends ParserMinimalBase
{
    private final MessagePackReader reader;
    // Null when CANONICALIZE_PROPERTY_NAMES is disabled.
    private final ByteQuadsCanonicalizer symbols;

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private MessagePackReadContext streamReadContext;

    private boolean isClosed;
    private long tokenPosition;
    private long currentPosition;
    private final IOContext ioContext;
    private ExtensionTypeCustomDeserializers extTypeCustomDesers;

    private enum Type
    {
        INT, LONG, DOUBLE, STRING, BYTES, BOOL, BIG_INT, EXT, NULL
    }
    private Type type;
    private int intValue;
    private long longValue;
    private double doubleValue;
    private boolean booleanValue;
    private byte[] bytesValue;
    private String stringValue;
    private BigInteger biValue;
    private MessagePackExtensionType extensionTypeValue;

    MessagePackParser(ObjectReadContext readCtxt,
            IOContext ioCtxt,
            int streamReadFeatures,
            MessagePackReader reader,
            ByteQuadsCanonicalizer symbols)
    {
        super(readCtxt, ioCtxt, streamReadFeatures);

        ioContext = ioCtxt;
        DupDetector dups = StreamReadFeature.STRICT_DUPLICATE_DETECTION.enabledIn(streamReadFeatures)
                ? DupDetector.rootDetector(this) : null;
        streamReadContext = MessagePackReadContext.createRootContext(dups);
        this.reader = reader;
        this.symbols = symbols;
    }

    public void setExtensionTypeCustomDeserializers(ExtensionTypeCustomDeserializers extTypeCustomDesers)
    {
        this.extTypeCustomDesers = extTypeCustomDesers;
    }

    @Override
    public Version version()
    {
        return PackageVersion.VERSION;
    }

    @Override
    public JsonToken nextToken() throws JacksonException
    {
        try {
            return _nextToken();
        }
        catch (EOFException e) {
            throw new UnexpectedEndOfInputException(this, _currToken, e.getMessage());
        }
        catch (IOException e) {
            throw _wrapIOFailure(e);
        }
    }

    private JsonToken _nextToken() throws IOException
    {
        type = null;
        tokenPosition = reader.getTotalReadBytes();

        boolean isObjectValueSet = streamReadContext.inObject() && _currToken != JsonToken.PROPERTY_NAME;
        if (isObjectValueSet) {
            if (!streamReadContext.expectMoreValues()) {
                streamReadContext = streamReadContext.getParent();
                return _updateToken(JsonToken.END_OBJECT);
            }
        }
        else if (streamReadContext.inArray()) {
            if (!streamReadContext.expectMoreValues()) {
                streamReadContext = streamReadContext.getParent();
                return _updateToken(JsonToken.END_ARRAY);
            }
        }

        if (!reader.hasNext()) {
            if (streamReadContext.inRoot()) {
                return null;
            }
            throw new UnexpectedEndOfInputException(this, null, null);
        }

        MessageFormat format = reader.getNextFormat();
        if (format == MessageFormat.NEVER_USED) {
            // The one format byte (0xc1) the spec reserves; it has no value type.
            return _reportError("Unexpected MessagePack format byte: 0xc1");
        }
        ValueType valueType = format.getValueType();

        JsonToken nextToken;
        switch (valueType) {
            case STRING: {
                type = Type.STRING;
                // Checked against the declared byte length before anything is allocated, so a
                // small hostile input cannot request a huge buffer. A UTF-8 string has at most
                // as many chars as bytes, so this is at least as strict as checking the result.
                int len = reader.unpackRawStringHeader();
                _streamReadConstraints.validateStringLength(len);
                if (isObjectValueSet) {
                    stringValue = symbols == null ? reader.readString(len) : reader.readName(len, symbols);
                    streamReadContext.setCurrentName(stringValue);
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    stringValue = reader.readString(len);
                    nextToken = JsonToken.VALUE_STRING;
                }
                break;
            }
            case INTEGER:
                Object v;
                switch (format) {
                    case UINT64:
                        BigInteger bi = reader.unpackBigInteger();
                        if (0 <= bi.compareTo(LONG_MIN) && bi.compareTo(LONG_MAX) <= 0) {
                            type = Type.LONG;
                            longValue = bi.longValue();
                            v = longValue;
                        }
                        else {
                            type = Type.BIG_INT;
                            biValue = bi;
                            v = biValue;
                        }
                        break;
                    default:
                        long l = reader.unpackLong();
                        if (Integer.MIN_VALUE <= l && l <= Integer.MAX_VALUE) {
                            type = Type.INT;
                            intValue = (int) l;
                            v = intValue;
                        }
                        else {
                            type = Type.LONG;
                            longValue = l;
                            v = longValue;
                        }
                        break;
                }

                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(String.valueOf(v));
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_NUMBER_INT;
                }
                break;
            case NIL:
                type = Type.NULL;
                reader.unpackNil();
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(null);
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_NULL;
                }
                break;
            case BOOLEAN:
                boolean b = reader.unpackBoolean();
                type = Type.BOOL;
                booleanValue = b;
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(Boolean.toString(b));
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = b ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE;
                }
                break;
            case FLOAT:
                type = Type.DOUBLE;
                doubleValue = reader.unpackDouble();
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(String.valueOf(doubleValue));
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_NUMBER_FLOAT;
                }
                break;
            case BINARY:
                type = Type.BYTES;
                int len = reader.unpackBinaryHeader();
                _streamReadConstraints.validateStringLength(len);
                bytesValue = reader.readPayload(len);
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(new String(bytesValue, StandardCharsets.UTF_8));
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_EMBEDDED_OBJECT;
                }
                break;
            case ARRAY:
                nextToken = JsonToken.START_ARRAY;
                streamReadContext = streamReadContext.createChildArrayContext(reader.unpackArrayHeader());
                _streamReadConstraints.validateNestingDepth(streamReadContext.getNestingDepth());
                break;
            case MAP:
                nextToken = JsonToken.START_OBJECT;
                streamReadContext = streamReadContext.createChildObjectContext(reader.unpackMapHeader());
                _streamReadConstraints.validateNestingDepth(streamReadContext.getNestingDepth());
                break;
            case EXTENSION:
                type = Type.EXT;
                ExtensionTypeHeader header = reader.unpackExtensionTypeHeader();
                _streamReadConstraints.validateStringLength(header.getLength());
                extensionTypeValue = new MessagePackExtensionType(header.getType(), reader.readPayload(header.getLength()));
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(deserializedExtensionTypeValue().toString());
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_EMBEDDED_OBJECT;
                }
                break;
            default:
                nextToken = _reportError("Unexpected MessagePack format type: " + valueType);
        }
        currentPosition = reader.getTotalReadBytes();

        _updateToken(nextToken);

        return nextToken;
    }

    @Override
    protected void _handleEOF()
    {
    }

    @Override
    public String getString()
    {
        if (type == null) {
            return _currToken == null ? null : _currToken.asString();
        }
        switch (type) {
            case STRING:
                return stringValue;
            case BYTES:
                return new String(bytesValue, StandardCharsets.UTF_8);
            case INT:
                return String.valueOf(intValue);
            case LONG:
                return String.valueOf(longValue);
            case DOUBLE:
                return String.valueOf(doubleValue);
            case BOOL:
                return Boolean.toString(booleanValue);
            case BIG_INT:
                return String.valueOf(biValue);
            case EXT:
                try {
                    return deserializedExtensionTypeValue().toString();
                }
                catch (IOException e) {
                    throw _wrapIOFailure(e);
                }
            case NULL:
                return "null";
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public char[] getStringCharacters()
    {
        return getString().toCharArray();
    }

    @Override
    public boolean hasStringCharacters()
    {
        return false;
    }

    @Override
    public int getStringLength()
    {
        return getString().length();
    }

    @Override
    public int getStringOffset()
    {
        return 0;
    }

    @Override
    public byte[] getBinaryValue(Base64Variant b64variant)
    {
        if (type == null) {
            return _reportError("Current token (" + _currToken + ") not of binary type");
        }
        switch (type) {
            case BYTES:
                return bytesValue;
            case STRING:
                return stringValue.getBytes(StandardCharsets.UTF_8);
            case EXT:
                return extensionTypeValue.getData();
            case INT:
            case LONG:
            case DOUBLE:
            case BOOL:
            case BIG_INT:
            case NULL:
                return _reportError("Current token (" + _currToken + ") not of binary type");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public Number getNumberValue()
    {
        if (type == null) {
            return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
        }
        switch (type) {
            case INT:
                return intValue;
            case LONG:
                return longValue;
            case DOUBLE:
                return doubleValue;
            case BIG_INT:
                return biValue;
            case NULL:
            case BOOL:
            case STRING:
            case BYTES:
            case EXT:
                return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public int getIntValue()
    {
        if (type == null) {
            return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
        }
        switch (type) {
            case INT:
                return intValue;
            case LONG:
                if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                    return _reportError("Numeric value (" + longValue + ") out of range for `int`");
                }
                return (int) longValue;
            case DOUBLE:
                if (!Double.isFinite(doubleValue)) {
                    return _reportError("Cannot convert non-finite double (" + doubleValue + ") to `int`");
                }
                if (doubleValue < Integer.MIN_VALUE || doubleValue > Integer.MAX_VALUE) {
                    return _reportError("Numeric value (" + doubleValue + ") out of range for `int`");
                }
                return (int) doubleValue;
            case BIG_INT:
                try {
                    return biValue.intValueExact();
                }
                catch (ArithmeticException e) {
                    return _reportError("Numeric value (" + biValue + ") out of range for `int`");
                }
            case NULL:
            case BOOL:
            case STRING:
            case BYTES:
            case EXT:
                return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public long getLongValue()
    {
        if (type == null) {
            return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
        }
        switch (type) {
            case INT:
                return intValue;
            case LONG:
                return longValue;
            case DOUBLE:
                if (!Double.isFinite(doubleValue)) {
                    return _reportError("Cannot convert non-finite double (" + doubleValue + ") to `long`");
                }
                // (double)Long.MAX_VALUE rounds up to 2^63; use >= to reject 2^63 itself.
                if (doubleValue < Long.MIN_VALUE || doubleValue >= (double) Long.MAX_VALUE) {
                    return _reportError("Numeric value (" + doubleValue + ") out of range for `long`");
                }
                return (long) doubleValue;
            case BIG_INT:
                try {
                    return biValue.longValueExact();
                }
                catch (ArithmeticException e) {
                    return _reportError("Numeric value (" + biValue + ") out of range for `long`");
                }
            case NULL:
            case BOOL:
            case STRING:
            case BYTES:
            case EXT:
                return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public BigInteger getBigIntegerValue()
    {
        if (type == null) {
            return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
        }
        switch (type) {
            case INT:
                return BigInteger.valueOf(intValue);
            case LONG:
                return BigInteger.valueOf(longValue);
            case DOUBLE:
                if (!Double.isFinite(doubleValue)) {
                    return _reportError("Cannot convert non-finite double (" + doubleValue + ") to BigInteger");
                }
                return BigDecimal.valueOf(doubleValue).toBigInteger(); // truncates fractional part
            case BIG_INT:
                return biValue;
            case NULL:
            case BOOL:
            case STRING:
            case BYTES:
            case EXT:
                return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public float getFloatValue()
    {
        // No bounds/range check: a finite double or large BigInteger may overflow to
        // Float.POSITIVE_INFINITY. This is intentional — same as ParserBase and CBORParser.
        if (type == null) {
            return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
        }
        switch (type) {
            case INT:
                return (float) intValue;
            case LONG:
                return (float) longValue;
            case DOUBLE:
                return (float) doubleValue;
            case BIG_INT:
                return biValue.floatValue();
            case NULL:
            case BOOL:
            case STRING:
            case BYTES:
            case EXT:
                return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public double getDoubleValue()
    {
        // No bounds/range check: large BigInteger may overflow to Double.POSITIVE_INFINITY,
        // and large long values may lose precision. Intentional — same as ParserBase.
        if (type == null) {
            return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
        }
        switch (type) {
            case INT:
                return intValue;
            case LONG:
                return (double) longValue;
            case DOUBLE:
                return doubleValue;
            case BIG_INT:
                return biValue.doubleValue();
            case NULL:
            case BOOL:
            case STRING:
            case BYTES:
            case EXT:
                return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public BigDecimal getDecimalValue()
    {
        if (type == null) {
            return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
        }
        switch (type) {
            case INT:
                return BigDecimal.valueOf(intValue);
            case LONG:
                return BigDecimal.valueOf(longValue);
            case DOUBLE:
                if (!Double.isFinite(doubleValue)) {
                    return _reportError("Cannot convert non-finite double (" + doubleValue + ") to BigDecimal");
                }
                return BigDecimal.valueOf(doubleValue);
            case BIG_INT:
                return new BigDecimal(biValue);
            case NULL:
            case BOOL:
            case STRING:
            case BYTES:
            case EXT:
                return _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    private Object deserializedExtensionTypeValue()
            throws IOException
    {
        if (extTypeCustomDesers != null) {
            ExtensionTypeCustomDeserializers.Deser deser = extTypeCustomDesers.getDeser(extensionTypeValue.getType());
            if (deser != null) {
                return deser.deserialize(extensionTypeValue.getData());
            }
        }
        return extensionTypeValue;
    }

    @Override
    public Object getEmbeddedObject()
    {
        if (type == null) {
            return _reportError("Current token (" + _currToken + ") not of embeddable type");
        }
        switch (type) {
            case BYTES:
                return bytesValue;
            case EXT:
                try {
                    return deserializedExtensionTypeValue();
                }
                catch (IOException e) {
                    throw _wrapIOFailure(e);
                }
            case INT:
            case LONG:
            case DOUBLE:
            case BOOL:
            case BIG_INT:
            case STRING:
            case NULL:
                return _reportError("Current token (" + _currToken + ") not of embeddable type");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public NumberType getNumberType()
    {
        if (type == null) {
            return null;
        }
        switch (type) {
            case INT:
                return NumberType.INT;
            case LONG:
                return NumberType.LONG;
            case DOUBLE:
                return NumberType.DOUBLE;
            case BIG_INT:
                return NumberType.BIG_INTEGER;
            case NULL:
            case BOOL:
            case STRING:
            case BYTES:
            case EXT:
                return null;
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    protected void _closeInput() throws IOException
    {
        if (StreamReadFeature.AUTO_CLOSE_SOURCE.enabledIn(_streamReadFeatures)) {
            reader.close();
        }
    }

    @Override
    protected void _releaseBuffers()
    {
        reader.release();
        if (symbols != null) {
            // Hands names learned by this parser back to the factory's shared table.
            symbols.release();
        }
    }

    @Override
    public boolean isClosed()
    {
        return isClosed;
    }

    @Override
    public void close()
    {
        if (isClosed) {
            return;
        }
        // Parsers are single-threaded by contract; close() is expected on the same
        // thread that created the parser. Cross-thread close is not a supported use case.
        try {
            _closeInput();
        }
        catch (IOException e) {
            throw _wrapIOFailure(e);
        }
        finally {
            isClosed = true;
            _releaseBuffers();
            ioContext.close();
        }
    }

    @Override
    public TokenStreamContext streamReadContext()
    {
        return streamReadContext;
    }

    @Override
    public TokenStreamLocation currentTokenLocation()
    {
        // columnNr repurposed as byte offset; truncates for inputs > 2 GB
        return new TokenStreamLocation(ioContext.contentReference(), tokenPosition, -1, (int) tokenPosition);
    }

    @Override
    public TokenStreamLocation currentLocation()
    {
        // columnNr repurposed as byte offset; truncates for inputs > 2 GB
        return new TokenStreamLocation(ioContext.contentReference(), currentPosition, -1, (int) currentPosition);
    }

    @Override
    public String currentName()
    {
        // Simple, but need to look for START_OBJECT/ARRAY's "off-by-one" thing:
        if (_currToken == JsonToken.START_OBJECT || _currToken == JsonToken.START_ARRAY) {
            MessagePackReadContext parent = streamReadContext.getParent();
            return parent.currentName();
        }
        return streamReadContext.currentName();
    }

    @Override
    public Object streamReadInputSource()
    {
        return ioContext.contentReference().getRawContent();
    }

    @Override
    public Object currentValue()
    {
        return streamReadContext.currentValue();
    }

    @Override
    public void assignCurrentValue(Object v)
    {
        streamReadContext.assignCurrentValue(v);
    }

    @Override
    public boolean isNaN()
    {
        if (type == Type.DOUBLE) {
            return Double.isNaN(doubleValue) || Double.isInfinite(doubleValue);
        }
        return false;
    }

    public boolean isCurrentFieldId()
    {
        return this.type == Type.INT || this.type == Type.LONG;
    }
}
