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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    // A type that is a map as a value is still a string as a key (its toString()), never a map.
    @Test
    public void aPojoKeyIsNeverAContainer() throws IOException
    {
        assertEquals("1,2", roundTrip(INTEGER_KEYS, new Point(), ValueType.STRING,
                new TypeReference<Map<Object, String>>() {}));
    }
}
