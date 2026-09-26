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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Writes a one-entry map through {@link MessagePackKeySerializer} and reads it back, checking
 * both what the key became on the wire and whether it survived.
 */
public class MapKeyRoundTripTest
{
    private static final ObjectMapper MAPPER = MessagePackMapper.builder()
            .addModule(new SimpleModule().addKeySerializer(Object.class, new MessagePackKeySerializer()))
            .build();

    enum Color
    {
        RED
    }

    // A key type with @JsonValue, the Jackson way to give a POJO a scalar form.
    public static class Code
    {
        private final String value;

        @JsonCreator
        public Code(String value)
        {
            this.value = value;
        }

        @JsonValue
        public String value()
        {
            return value;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Code && ((Code) o).value.equals(value);
        }

        @Override
        public int hashCode()
        {
            return value.hashCode();
        }
    }

    // A key type with no annotations: written with toString(), read with the String constructor.
    public static class Name
    {
        private final String value;

        public Name(String value)
        {
            this.value = value;
        }

        @Override
        public String toString()
        {
            return value;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Name && ((Name) o).value.equals(value);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(value);
        }
    }

    // A key type Jackson would serialize as a map if it were a value.
    public static class Point
    {
        public int x = 1;
        public int y = 2;

        @Override
        public String toString()
        {
            return x + "," + y;
        }
    }

    private static byte[] write(Object key)
    {
        return MAPPER.writeValueAsBytes(Collections.singletonMap(key, "v"));
    }

    private static ValueType keyType(byte[] bytes) throws IOException
    {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
            assertEquals(1, unpacker.unpackMapHeader());
            return unpacker.getNextFormat().getValueType();
        }
    }

    private static <K> K readKey(ObjectMapper mapper, byte[] bytes, TypeReference<Map<K, String>> type)
    {
        Map<K, String> map = mapper.readValue(bytes, type);
        assertEquals(1, map.size());
        return map.keySet().iterator().next();
    }

    private static <K> void assertRoundTrip(K key, ValueType wireType, TypeReference<Map<K, String>> type)
            throws IOException
    {
        byte[] bytes = write(key);
        assertEquals(wireType, keyType(bytes), () -> "wire type of " + key);
        assertEquals(key, readKey(MAPPER, bytes, type));
    }

    @Test
    public void stringLikeKeysAreWrittenAsStrings() throws IOException
    {
        assertRoundTrip("ké", ValueType.STRING, new TypeReference<Map<String, String>>() {});
        assertRoundTrip("", ValueType.STRING, new TypeReference<Map<String, String>>() {});
        assertRoundTrip('a', ValueType.STRING, new TypeReference<Map<Character, String>>() {});
    }

    @Test
    public void integerKeysAreWrittenAsIntegers() throws IOException
    {
        assertRoundTrip(Byte.MIN_VALUE, ValueType.INTEGER, new TypeReference<Map<Byte, String>>() {});
        assertRoundTrip(Byte.MAX_VALUE, ValueType.INTEGER, new TypeReference<Map<Byte, String>>() {});
        assertRoundTrip(Short.MIN_VALUE, ValueType.INTEGER, new TypeReference<Map<Short, String>>() {});
        assertRoundTrip(Short.MAX_VALUE, ValueType.INTEGER, new TypeReference<Map<Short, String>>() {});
        assertRoundTrip(Integer.MIN_VALUE, ValueType.INTEGER, new TypeReference<Map<Integer, String>>() {});
        assertRoundTrip(Integer.MAX_VALUE, ValueType.INTEGER, new TypeReference<Map<Integer, String>>() {});
        assertRoundTrip(0, ValueType.INTEGER, new TypeReference<Map<Integer, String>>() {});
        assertRoundTrip(Long.MIN_VALUE, ValueType.INTEGER, new TypeReference<Map<Long, String>>() {});
        assertRoundTrip(Long.MAX_VALUE, ValueType.INTEGER, new TypeReference<Map<Long, String>>() {});
        assertRoundTrip(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), ValueType.INTEGER,
                new TypeReference<Map<BigInteger, String>>() {});
    }

    @Test
    public void floatingPointKeysAreWrittenAsFloats() throws IOException
    {
        assertRoundTrip(0.1f, ValueType.FLOAT, new TypeReference<Map<Float, String>>() {});
        assertRoundTrip(Float.MAX_VALUE, ValueType.FLOAT, new TypeReference<Map<Float, String>>() {});
        assertRoundTrip(0.1, ValueType.FLOAT, new TypeReference<Map<Double, String>>() {});
        assertRoundTrip(Double.MIN_VALUE, ValueType.FLOAT, new TypeReference<Map<Double, String>>() {});
        assertRoundTrip(Double.NaN, ValueType.FLOAT, new TypeReference<Map<Double, String>>() {});
    }

    @Test
    public void booleanKeysAreWrittenAsBooleans() throws IOException
    {
        assertRoundTrip(true, ValueType.BOOLEAN, new TypeReference<Map<Boolean, String>>() {});
        assertRoundTrip(false, ValueType.BOOLEAN, new TypeReference<Map<Boolean, String>>() {});
    }

    // Types MessagePack has no scalar for are written as Jackson writes them as JSON keys.
    @Test
    public void otherKeysAreWrittenAsJacksonKeyStrings() throws IOException
    {
        assertRoundTrip(Color.RED, ValueType.STRING, new TypeReference<Map<Color, String>>() {});
        assertRoundTrip(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), ValueType.STRING,
                new TypeReference<Map<UUID, String>>() {});
        assertRoundTrip(new Date(1700000000123L), ValueType.STRING, new TypeReference<Map<Date, String>>() {});
        assertRoundTrip(Instant.ofEpochSecond(1700000000L, 123000000), ValueType.STRING,
                new TypeReference<Map<Instant, String>>() {});
        assertRoundTrip(new Code("c-1"), ValueType.STRING, new TypeReference<Map<Code, String>>() {});
        assertRoundTrip(new Name("n-1"), ValueType.STRING, new TypeReference<Map<Name, String>>() {});
    }

    // A POJO that would serialize to a map as a value becomes its toString() as a key, as in JSON.
    @Test
    public void aPojoKeyIsNeverWrittenAsAContainer() throws IOException
    {
        byte[] bytes = write(new Point());
        assertEquals(ValueType.STRING, keyType(bytes));
        assertEquals("1,2", readKey(MAPPER, bytes, new TypeReference<Map<String, String>>() {}));
    }

    // Written as a MessagePack number, so the scale does not survive.
    @Test
    public void aBigDecimalKeyLosesItsScale() throws IOException
    {
        byte[] bytes = write(new BigDecimal("1.10"));
        assertEquals(ValueType.FLOAT, keyType(bytes));
        BigDecimal back = readKey(MAPPER, bytes, new TypeReference<Map<BigDecimal, String>>() {});
        assertEquals(0, new BigDecimal("1.10").compareTo(back));
        assertNotEquals(new BigDecimal("1.10"), back);
    }

    // A binary key is read as its UTF-8 decoding, because a property name is a String, so only
    // bytes that are valid UTF-8 come back unchanged.
    @Test
    public void aBinaryKeySurvivesOnlyIfItIsValidUtf8() throws IOException
    {
        ObjectMapper mapper = MessagePackMapper.builder()
                .addModule(new SimpleModule()
                        .addKeySerializer(Object.class, new MessagePackKeySerializer())
                        .addKeyDeserializer(byte[].class, new KeyDeserializer()
                        {
                            @Override
                            public Object deserializeKey(String key, DeserializationContext ctxt)
                            {
                                return key.getBytes(StandardCharsets.UTF_8);
                            }
                        }))
                .build();
        TypeReference<Map<byte[], String>> type = new TypeReference<Map<byte[], String>>() {};

        byte[] utf8 = {'a', (byte) 0xc3, (byte) 0xa9};
        byte[] bytes = mapper.writeValueAsBytes(Collections.singletonMap(utf8, "v"));
        assertEquals(ValueType.BINARY, keyType(bytes));
        assertArrayEquals(utf8, readKey(mapper, bytes, type));

        byte[] notUtf8 = {1, (byte) 0xff};
        bytes = mapper.writeValueAsBytes(Collections.singletonMap(notUtf8, "v"));
        assertEquals(ValueType.BINARY, keyType(bytes));
        assertArrayEquals(new byte[] {1, (byte) 0xef, (byte) 0xbf, (byte) 0xbd}, readKey(mapper, bytes, type));
    }

    // An extension key reads back as the toString() of its deserialized value, so it survives
    // only with an extension deserializer whose result a KeyDeserializer can parse again.
    @Test
    public void anExtensionKeyNeedsAnExtensionDeserializer() throws IOException
    {
        MessagePackExtensionType ext = new MessagePackExtensionType((byte) 7, "abc".getBytes(StandardCharsets.UTF_8));
        byte[] bytes = write(ext);
        assertEquals(ValueType.EXTENSION, keyType(bytes));

        assertEquals(ext.toString(), readKey(MAPPER, bytes, new TypeReference<Map<String, String>>() {}));

        ExtensionTypeCustomDeserializers desers = new ExtensionTypeCustomDeserializers();
        desers.addCustomDeser((byte) 7, data -> new String(data, StandardCharsets.UTF_8));
        ObjectMapper withDeser = new MessagePackMapper(new MessagePackFactory().setExtTypeCustomDesers(desers));
        assertEquals("abc", readKey(withDeser, bytes, new TypeReference<Map<String, String>>() {}));
    }

    // The lookup that finds a key serializer for a non-scalar type must not end up back here.
    @Test
    public void aKeySerializerRegisteredForAPojoTypeDoesNotRecurse() throws IOException
    {
        ObjectMapper mapper = MessagePackMapper.builder()
                .addModule(new SimpleModule().addKeySerializer(Point.class, new MessagePackKeySerializer()))
                .build();
        byte[] bytes = mapper.writeValueAsBytes(Collections.singletonMap(new Point(), "v"));
        assertEquals(ValueType.STRING, keyType(bytes));
        assertEquals("1,2", readKey(mapper, bytes, new TypeReference<Map<String, String>>() {}));
    }
}
