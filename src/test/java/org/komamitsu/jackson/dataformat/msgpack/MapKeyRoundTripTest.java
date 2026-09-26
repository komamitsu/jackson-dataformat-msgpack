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
import com.fasterxml.jackson.annotation.JsonKey;
import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.module.SimpleModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes a one-entry map and reads it back, checking what the key became on the wire and
 * that it came back equal. Every key is either a string or, with integer keys enabled, an
 * integer, and the parser reads both back as property names.
 */
public class MapKeyRoundTripTest
{
    private static final ObjectMapper DEFAULT = new MessagePackMapper();
    private static final ObjectMapper INTEGER_KEYS =
            new MessagePackMapper(new MessagePackFactoryBuilder().supportIntegerKeys(true).build());

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

    // The README's example: @JsonKey gives the key's text, @JsonCreator builds it back.
    public static class UserId
    {
        private final String value;

        @JsonCreator
        public UserId(String value)
        {
            this.value = value;
        }

        @JsonKey
        public String value()
        {
            return value;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof UserId && ((UserId) o).value.equals(value);
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
            return value.hashCode();
        }
    }

    // A type that serializes to a map as a value.
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

    private static ValueType keyType(byte[] bytes) throws IOException
    {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
            assertEquals(1, unpacker.unpackMapHeader());
            return unpacker.getNextFormat().getValueType();
        }
    }

    private static <K> K roundTrip(ObjectMapper mapper, K key, ValueType wireType, TypeReference<Map<K, String>> type)
            throws IOException
    {
        byte[] bytes = mapper.writeValueAsBytes(Collections.singletonMap(key, "v"));
        assertEquals(wireType, keyType(bytes), () -> "wire type of " + key);
        Map<K, String> map = mapper.readValue(bytes, type);
        assertEquals(1, map.size());
        return map.keySet().iterator().next();
    }

    private static <K> void assertRoundTrip(ObjectMapper mapper, K key, ValueType wireType,
            TypeReference<Map<K, String>> type) throws IOException
    {
        assertEquals(key, roundTrip(mapper, key, wireType, type));
    }

    @Test
    public void integerKeysAreIntegersWhenEnabled() throws IOException
    {
        assertRoundTrip(INTEGER_KEYS, Byte.MIN_VALUE, ValueType.INTEGER, new TypeReference<Map<Byte, String>>() {});
        assertRoundTrip(INTEGER_KEYS, Byte.MAX_VALUE, ValueType.INTEGER, new TypeReference<Map<Byte, String>>() {});
        assertRoundTrip(INTEGER_KEYS, Short.MIN_VALUE, ValueType.INTEGER, new TypeReference<Map<Short, String>>() {});
        assertRoundTrip(INTEGER_KEYS, Short.MAX_VALUE, ValueType.INTEGER, new TypeReference<Map<Short, String>>() {});
        assertRoundTrip(INTEGER_KEYS, Integer.MIN_VALUE, ValueType.INTEGER, new TypeReference<Map<Integer, String>>() {});
        assertRoundTrip(INTEGER_KEYS, 0, ValueType.INTEGER, new TypeReference<Map<Integer, String>>() {});
        assertRoundTrip(INTEGER_KEYS, Integer.MAX_VALUE, ValueType.INTEGER, new TypeReference<Map<Integer, String>>() {});
        assertRoundTrip(INTEGER_KEYS, Long.MIN_VALUE, ValueType.INTEGER, new TypeReference<Map<Long, String>>() {});
        assertRoundTrip(INTEGER_KEYS, Long.MAX_VALUE, ValueType.INTEGER, new TypeReference<Map<Long, String>>() {});
    }

    // Without integer keys enabled the same keys are the decimal strings Jackson writes for JSON.
    @Test
    public void integerKeysAreStringsByDefault() throws IOException
    {
        assertRoundTrip(DEFAULT, Byte.MIN_VALUE, ValueType.STRING, new TypeReference<Map<Byte, String>>() {});
        assertRoundTrip(DEFAULT, Short.MAX_VALUE, ValueType.STRING, new TypeReference<Map<Short, String>>() {});
        assertRoundTrip(DEFAULT, Integer.MIN_VALUE, ValueType.STRING, new TypeReference<Map<Integer, String>>() {});
        assertRoundTrip(DEFAULT, Long.MAX_VALUE, ValueType.STRING, new TypeReference<Map<Long, String>>() {});
    }

    // Jackson has no integer-key path for these, so they stay strings even with integer keys on.
    @Test
    public void otherScalarKeysAreStrings() throws IOException
    {
        assertRoundTrip(INTEGER_KEYS, "ké", ValueType.STRING, new TypeReference<Map<String, String>>() {});
        assertRoundTrip(INTEGER_KEYS, "", ValueType.STRING, new TypeReference<Map<String, String>>() {});
        assertRoundTrip(INTEGER_KEYS, 'a', ValueType.STRING, new TypeReference<Map<Character, String>>() {});
        assertRoundTrip(INTEGER_KEYS, BigInteger.ONE.shiftLeft(64), ValueType.STRING,
                new TypeReference<Map<BigInteger, String>>() {});
        assertRoundTrip(INTEGER_KEYS, new BigDecimal("1.10"), ValueType.STRING,
                new TypeReference<Map<BigDecimal, String>>() {});
        assertRoundTrip(INTEGER_KEYS, 0.1f, ValueType.STRING, new TypeReference<Map<Float, String>>() {});
        assertRoundTrip(INTEGER_KEYS, Double.NaN, ValueType.STRING, new TypeReference<Map<Double, String>>() {});
        assertRoundTrip(INTEGER_KEYS, true, ValueType.STRING, new TypeReference<Map<Boolean, String>>() {});
    }

    @Test
    public void objectKeysAreJacksonKeyStrings() throws IOException
    {
        assertRoundTrip(INTEGER_KEYS, Color.RED, ValueType.STRING, new TypeReference<Map<Color, String>>() {});
        assertRoundTrip(INTEGER_KEYS, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), ValueType.STRING,
                new TypeReference<Map<UUID, String>>() {});
        assertRoundTrip(INTEGER_KEYS, new Date(1700000000123L), ValueType.STRING,
                new TypeReference<Map<Date, String>>() {});
        assertRoundTrip(INTEGER_KEYS, Instant.ofEpochSecond(1700000000L, 123000000), ValueType.STRING,
                new TypeReference<Map<Instant, String>>() {});
        assertRoundTrip(INTEGER_KEYS, new Code("c-1"), ValueType.STRING, new TypeReference<Map<Code, String>>() {});
        assertRoundTrip(INTEGER_KEYS, new UserId("u-1"), ValueType.STRING, new TypeReference<Map<UserId, String>>() {});
        assertRoundTrip(INTEGER_KEYS, new Name("n-1"), ValueType.STRING, new TypeReference<Map<Name, String>>() {});
    }

    // Jackson writes a byte[] key as Base64 text and decodes it back, so any bytes survive.
    @Test
    public void aByteArrayKeyIsBase64() throws IOException
    {
        byte[] key = {1, (byte) 0xff, 0};
        byte[] back = roundTrip(INTEGER_KEYS, key, ValueType.STRING, new TypeReference<Map<byte[], String>>() {});
        assertArrayEquals(key, back);
    }

    // Built from a String by a static valueOf(String), which Jackson's key deserializer accepts.
    public static class Token
    {
        private final String value;

        private Token(String value)
        {
            this.value = value;
        }

        public static Token valueOf(String value)
        {
            return new Token(value);
        }

        @Override
        public String toString()
        {
            return value;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Token && ((Token) o).value.equals(value);
        }

        @Override
        public int hashCode()
        {
            return value.hashCode();
        }
    }

    // Written through @JsonValue, but with no way to be built back from that string.
    public static class Label
    {
        @JsonValue
        public String text()
        {
            return "label";
        }
    }

    // Read back by a KeyDeserializer named on the class.
    @JsonDeserialize(keyUsing = Tagged.Reader.class)
    public static class Tagged
    {
        private final String value;

        Tagged(String value)
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
            return o instanceof Tagged && ((Tagged) o).value.equals(value);
        }

        @Override
        public int hashCode()
        {
            return value.hashCode();
        }

        public static class Reader
                extends KeyDeserializer
        {
            @Override
            public Object deserializeKey(String key, DeserializationContext ctxt)
            {
                return new Tagged(key);
            }
        }
    }

    @Test
    public void keysBuiltFromAStringRoundTrip() throws IOException
    {
        assertRoundTrip(INTEGER_KEYS, Token.valueOf("t-1"), ValueType.STRING, new TypeReference<Map<Token, String>>() {});
        assertRoundTrip(INTEGER_KEYS, new Tagged("g-1"), ValueType.STRING, new TypeReference<Map<Tagged, String>>() {});
        assertRoundTrip(INTEGER_KEYS, LocalDate.of(2026, 9, 26), ValueType.STRING,
                new TypeReference<Map<LocalDate, String>>() {});
    }

    private static void assertRefused(Object key)
    {
        InvalidDefinitionException e = assertThrows(InvalidDefinitionException.class,
                () -> INTEGER_KEYS.writeValueAsBytes(Collections.singletonMap(key, "v")));
        assertTrue(e.getMessage().contains("cannot be read back as a map key"), e.getMessage());
    }

    // Written as their toString(), these could never be read back, so they are refused on write.
    @Test
    public void aMapCollectionOrArrayKeyIsRefused()
    {
        assertRefused(Collections.singletonMap("a", 1));
        assertRefused(Arrays.asList(1, 2));
        assertRefused(new HashSet<>(Arrays.asList(1, 2)));
        assertRefused(new String[] {"a"});
    }

    // A POJO with no way to be built from its key string is refused, whether its text comes
    // from toString() or from @JsonValue.
    @Test
    public void aPojoKeyThatCannotBeBuiltFromAStringIsRefused()
    {
        assertRefused(new Point());
        assertRefused(new Label());
    }

    // The check applies to the runtime type when the declared key type is Object.
    @Test
    public void anObjectKeyIsCheckedByItsRuntimeType() throws IOException
    {
        Map<Object, String> map = new LinkedHashMap<>();
        map.put("s", "v");
        map.put(new Point(), "v");
        assertThrows(InvalidDefinitionException.class, () -> INTEGER_KEYS.writeValueAsBytes(map));

        Map<Object, String> readable = Collections.singletonMap(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "v");
        assertEquals(Collections.singletonMap("123e4567-e89b-12d3-a456-426614174000", "v"),
                INTEGER_KEYS.readValue(INTEGER_KEYS.writeValueAsBytes(readable), new TypeReference<Map<Object, String>>() {}));
    }

    // Readability is asked of the mapper's read side, so a KeyDeserializer registered on the
    // mapper makes a type writable, with no annotation on the type.
    @Test
    public void aKeyDeserializerRegisteredOnTheMapperMakesAKeyWritable() throws IOException
    {
        ObjectMapper mapper = MessagePackMapper.builder()
                .addModule(new SimpleModule().addKeyDeserializer(Point.class, new KeyDeserializer()
                {
                    @Override
                    public Object deserializeKey(String key, DeserializationContext ctxt)
                    {
                        return new Point();
                    }
                }))
                .build();
        byte[] bytes = mapper.writeValueAsBytes(Collections.singletonMap(new Point(), "v"));
        assertEquals(Collections.singletonMap("1,2", "v"), mapper.readValue(bytes, new TypeReference<Map<String, String>>() {}));
    }

    // JDK types are judged by Jackson's own key deserializers: Currency has one, Charset and
    // TimeZone do not.
    @Test
    public void jdkKeyTypesFollowJacksonsKeyDeserializers() throws IOException
    {
        assertRoundTrip(INTEGER_KEYS, Currency.getInstance("JPY"), ValueType.STRING,
                new TypeReference<Map<Currency, String>>() {});
        assertRefused(StandardCharsets.UTF_8);
        assertRefused(TimeZone.getTimeZone("UTC"));
    }

    // A rebuilt mapper gets its own guard, bound to itself.
    @Test
    public void aRebuiltMapperStillRefusesUnreadableKeys()
    {
        ObjectMapper rebuilt = INTEGER_KEYS.rebuild().build();
        assertThrows(InvalidDefinitionException.class,
                () -> rebuilt.writeValueAsBytes(Collections.singletonMap(new Point(), "v")));
    }

    // A mapper restored by JDK serialization is rebuilt through its builder, so it gets a guard
    // bound to itself.
    @Test
    public void aJdkDeserializedMapperStillGuardsKeys() throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(INTEGER_KEYS);
        }
        ObjectMapper restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (ObjectMapper) in.readObject();
        }
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals(Collections.singletonMap(uuid, "v"), restored.readValue(
                restored.writeValueAsBytes(Collections.singletonMap(uuid, "v")), new TypeReference<Map<UUID, String>>() {}));
        assertThrows(InvalidDefinitionException.class,
                () -> restored.writeValueAsBytes(Collections.singletonMap(new Point(), "v")));
    }

    // A Short or Byte key serializer the user registers wins over the built-in one.
    @Test
    public void aUserKeySerializerForShortWins() throws IOException
    {
        ObjectMapper mapper = MessagePackMapper.builder(new MessagePackFactoryBuilder().supportIntegerKeys(true).build())
                .addModule(new SimpleModule().addKeySerializer(Short.class, new ValueSerializer<Short>()
                {
                    @Override
                    public void serialize(Short value, JsonGenerator gen, SerializationContext ctxt)
                    {
                        gen.writeName("s" + value);
                    }
                }))
                .build();
        byte[] bytes = mapper.writeValueAsBytes(Collections.singletonMap((short) 7, "v"));
        assertEquals(ValueType.STRING, keyType(bytes));
        assertEquals(Collections.singletonMap("s7", "v"), mapper.readValue(bytes, new TypeReference<Map<String, String>>() {}));
    }

    public static class PointKeyReader
            extends KeyDeserializer
    {
        @Override
        public Object deserializeKey(String key, DeserializationContext ctxt)
        {
            return new Point();
        }
    }

    public static class Points
    {
        @JsonDeserialize(keyUsing = PointKeyReader.class)
        public Map<Point, String> byPoint = Collections.singletonMap(new Point(), "v");
    }

    // A key deserializer named on the map property makes its keys readable, so they are written.
    @Test
    public void aPropertyLevelKeyDeserializerMakesAKeyWritable() throws IOException
    {
        byte[] bytes = INTEGER_KEYS.writeValueAsBytes(new Points());
        Points back = INTEGER_KEYS.readValue(bytes, Points.class);
        assertEquals(1, back.byPoint.size());
        assertEquals("v", back.byPoint.values().iterator().next());
    }

    // A key serializer the user registers is trusted: reading back is then the user's contract.
    @Test
    public void aRegisteredKeySerializerIsTrusted() throws IOException
    {
        ObjectMapper mapper = MessagePackMapper.builder()
                .addModule(new SimpleModule().addKeySerializer(Point.class, new ValueSerializer<Point>()
                {
                    @Override
                    public void serialize(Point value, JsonGenerator gen, SerializationContext ctxt)
                    {
                        gen.writeName(value.x + ":" + value.y);
                    }
                }))
                .build();
        byte[] bytes = mapper.writeValueAsBytes(Collections.singletonMap(new Point(), "v"));
        assertEquals(Collections.singletonMap("1:2", "v"), mapper.readValue(bytes, new TypeReference<Map<String, String>>() {}));
    }
}
