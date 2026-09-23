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

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JacksonException;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.exc.StreamWriteException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;
import org.msgpack.core.ExtensionTypeHeader;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.buffer.ArrayBufferInput;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.MatcherAssert.assertThat;

public class MessagePackGeneratorTest
        extends MessagePackDataformatTestBase
{
    @Test
    public void testGeneratorShouldWriteObject()
            throws IOException
    {
        Map<String, Object> hashMap = new HashMap<String, Object>();
        // #1
        hashMap.put("str", "komamitsu");
        // #2
        hashMap.put("boolean", true);
        // #3
        hashMap.put("int", Integer.MAX_VALUE);
        // #4
        hashMap.put("long", Long.MIN_VALUE);
        // #5
        hashMap.put("float", 3.14159f);
        // #6
        hashMap.put("double", 3.14159d);
        // #7
        hashMap.put("bin", new byte[] {0x00, 0x01, (byte) 0xFE, (byte) 0xFF});
        // #8
        Map<String, Object> childObj = new HashMap<String, Object>();
        childObj.put("co_str", "child#0");
        childObj.put("co_int", 12345);
        hashMap.put("childObj", childObj);
        // #9
        List<Object> childArray = new ArrayList<Object>();
        childArray.add("child#1");
        childArray.add(1.23f);
        hashMap.put("childArray", childArray);
        // #10
        byte[] hello = "hello".getBytes("UTF-8");
        hashMap.put("ext", new MessagePackExtensionType((byte) 17, hello));

        long bitmap = 0;
        byte[] bytes = objectMapper.writeValueAsBytes(hashMap);
        MessageUnpacker messageUnpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(bytes));
        assertEquals(hashMap.size(), messageUnpacker.unpackMapHeader());
        for (int i = 0; i < hashMap.size(); i++) {
            String key = messageUnpacker.unpackString();
            if (key.equals("str")) {
                // #1
                assertEquals("komamitsu", messageUnpacker.unpackString());
                bitmap |= 0x1 << 0;
            }
            else if (key.equals("boolean")) {
                // #2
                assertTrue(messageUnpacker.unpackBoolean());
                bitmap |= 0x1 << 1;
            }
            else if (key.equals("int")) {
                // #3
                assertEquals(Integer.MAX_VALUE, messageUnpacker.unpackInt());
                bitmap |= 0x1 << 2;
            }
            else if (key.equals("long")) {
                // #4
                assertEquals(Long.MIN_VALUE, messageUnpacker.unpackLong());
                bitmap |= 0x1 << 3;
            }
            else if (key.equals("float")) {
                // #5
                assertEquals(3.14159f, messageUnpacker.unpackFloat(), 0.01f);
                bitmap |= 0x1 << 4;
            }
            else if (key.equals("double")) {
                // #6
                assertEquals(3.14159d, messageUnpacker.unpackDouble(), 0.01f);
                bitmap |= 0x1 << 5;
            }
            else if (key.equals("bin")) {
                // #7
                assertEquals(4, messageUnpacker.unpackBinaryHeader());
                assertEquals((byte) 0x00, messageUnpacker.unpackByte());
                assertEquals((byte) 0x01, messageUnpacker.unpackByte());
                assertEquals((byte) 0xFE, messageUnpacker.unpackByte());
                assertEquals((byte) 0xFF, messageUnpacker.unpackByte());
                bitmap |= 0x1 << 6;
            }
            else if (key.equals("childObj")) {
                // #8
                assertEquals(2, messageUnpacker.unpackMapHeader());
                for (int j = 0; j < 2; j++) {
                    String childKey = messageUnpacker.unpackString();
                    if (childKey.equals("co_str")) {
                        assertEquals("child#0", messageUnpacker.unpackString());
                        bitmap |= 0x1 << 7;
                    }
                    else if (childKey.equals("co_int")) {
                        assertEquals(12345, messageUnpacker.unpackInt());
                        bitmap |= 0x1 << 8;
                    }
                    else {
                        assertTrue(false);
                    }
                }
            }
            else if (key.equals("childArray")) {
                // #9
                assertEquals(2, messageUnpacker.unpackArrayHeader());
                assertEquals("child#1", messageUnpacker.unpackString());
                assertEquals(1.23f, messageUnpacker.unpackFloat(), 0.01f);
                bitmap |= 0x1 << 9;
            }
            else if (key.equals("ext")) {
                // #10
                ExtensionTypeHeader header = messageUnpacker.unpackExtensionTypeHeader();
                assertEquals(17, header.getType());
                assertEquals(5, header.getLength());
                ByteBuffer payload = ByteBuffer.allocate(header.getLength());
                payload.flip();
                payload.limit(payload.capacity());
                messageUnpacker.readPayload(payload);
                payload.flip();
                assertArrayEquals("hello".getBytes(), payload.array());
                bitmap |= 0x1 << 10;
            }
            else {
                assertTrue(false);
            }
        }
        assertEquals(0x07FF, bitmap);
    }

    @Test
    public void testGeneratorShouldWriteArray()
            throws IOException
    {
        List<Object> array = new ArrayList<Object>();
        // #1
        array.add("komamitsu");
        // #2
        array.add(Integer.MAX_VALUE);
        // #3
        array.add(Long.MIN_VALUE);
        // #4
        array.add(3.14159f);
        // #5
        array.add(3.14159d);
        // #6
        Map<String, Object> childObject = new HashMap<String, Object>();
        childObject.put("str", "foobar");
        childObject.put("num", 123456);
        array.add(childObject);
        // #7
        array.add(false);

        long bitmap = 0;
        byte[] bytes = objectMapper.writeValueAsBytes(array);
        MessageUnpacker messageUnpacker = MessagePack.newDefaultUnpacker(new ArrayBufferInput(bytes));
        assertEquals(array.size(), messageUnpacker.unpackArrayHeader());
        // #1
        assertEquals("komamitsu", messageUnpacker.unpackString());
        // #2
        assertEquals(Integer.MAX_VALUE, messageUnpacker.unpackInt());
        // #3
        assertEquals(Long.MIN_VALUE, messageUnpacker.unpackLong());
        // #4
        assertEquals(3.14159f, messageUnpacker.unpackFloat(), 0.01f);
        // #5
        assertEquals(3.14159d, messageUnpacker.unpackDouble(), 0.01f);
        // #6
        assertEquals(2, messageUnpacker.unpackMapHeader());
        for (int i = 0; i < childObject.size(); i++) {
            String key = messageUnpacker.unpackString();
            if (key.equals("str")) {
                assertEquals("foobar", messageUnpacker.unpackString());
                bitmap |= 0x1 << 0;
            }
            else if (key.equals("num")) {
                assertEquals(123456, messageUnpacker.unpackInt());
                bitmap |= 0x1 << 1;
            }
            else {
                assertTrue(false);
            }
        }
        assertEquals(0x3, bitmap);
        // #7
        assertEquals(false, messageUnpacker.unpackBoolean());
    }

    @Test
    public void testMessagePackGeneratorDirectly()
            throws Exception
    {
        MessagePackFactory messagePackFactory = new MessagePackFactory();
        File tempFile = createTempFile();

        JsonGenerator generator = messagePackFactory.createGenerator(ObjectWriteContext.empty(), tempFile, JsonEncoding.UTF8);
        assertTrue(generator instanceof MessagePackGenerator);
        generator.writeStartArray();
        generator.writeNumber(0);
        generator.writeString("one");
        generator.writeNumber(2.0f);
        generator.writeEndArray();
        generator.flush();
        generator.flush();      // intentional
        generator.close();

        FileInputStream fileInputStream = new FileInputStream(tempFile);
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(fileInputStream);
        assertEquals(3, unpacker.unpackArrayHeader());
        assertEquals(0, unpacker.unpackInt());
        assertEquals("one", unpacker.unpackString());
        assertEquals(2.0f, unpacker.unpackFloat(), 0.001f);
        assertFalse(unpacker.hasNext());
    }

    @Test
    public void testWritePrimitives()
            throws Exception
    {
        MessagePackFactory messagePackFactory = new MessagePackFactory();
        File tempFile = createTempFile();

        JsonGenerator generator = messagePackFactory.createGenerator(ObjectWriteContext.empty(), tempFile, JsonEncoding.UTF8);
        assertTrue(generator instanceof MessagePackGenerator);
        generator.writeNumber(0);
        generator.writeString("one");
        generator.writeNumber(2.0f);
        generator.writeString("三");
        generator.writeString("444④");
        generator.flush();
        generator.close();

        FileInputStream fileInputStream = new FileInputStream(tempFile);
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(fileInputStream);
        assertEquals(0, unpacker.unpackInt());
        assertEquals("one", unpacker.unpackString());
        assertEquals(2.0f, unpacker.unpackFloat(), 0.001f);
        assertEquals("三", unpacker.unpackString());
        assertEquals("444④", unpacker.unpackString());
        assertFalse(unpacker.hasNext());
    }

    @Test
    public void testBigDecimal()
            throws IOException
    {
        ObjectMapper mapper = new MessagePackMapper(new MessagePackFactory());

        {
            double d0 = 1.23456789;
            double d1 = 1.23450000000000000000006789;
            String d2 = "12.30";
            String d3 = "0.00001";
            List<BigDecimal> bigDecimals = Arrays.asList(
                    BigDecimal.valueOf(d0),
                    BigDecimal.valueOf(d1),
                    new BigDecimal(d2),
                    new BigDecimal(d3),
                    BigDecimal.valueOf(Double.MIN_VALUE),
                    BigDecimal.valueOf(Double.MAX_VALUE),
                    BigDecimal.valueOf(Double.MIN_NORMAL)
            );

            byte[] bytes = mapper.writeValueAsBytes(bigDecimals);
            MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes);

            assertEquals(bigDecimals.size(), unpacker.unpackArrayHeader());
            assertEquals(d0, unpacker.unpackDouble(), 0.000000000000001);
            assertEquals(d1, unpacker.unpackDouble(), 0.000000000000001);
            assertEquals(Double.valueOf(d2), unpacker.unpackDouble(), 0.000000000000001);
            assertEquals(Double.valueOf(d3), unpacker.unpackDouble(), 0.000000000000001);
            assertEquals(Double.MIN_VALUE, unpacker.unpackDouble(), 0.000000000000001);
            assertEquals(Double.MAX_VALUE, unpacker.unpackDouble(), 0.000000000000001);
            assertEquals(Double.MIN_NORMAL, unpacker.unpackDouble(), 0.000000000000001);
        }

        {
            BigDecimal decimal = new BigDecimal("1234.567890123456789012345678901234567890");
            List<BigDecimal> bigDecimals = Arrays.asList(
                    decimal
            );

            // Raised while the element is being written, so databind wraps it with the path.
            DatabindException e = assertThrows(DatabindException.class, () -> mapper.writeValueAsBytes(bigDecimals));
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
        }
    }

    @Test
    public void testBigDecimalCompareTo()
            throws IOException
    {
        ObjectMapper mapper = new MessagePackMapper(new MessagePackFactory());

        // BigDecimal with trailing zeros is representable as double — must not throw
        BigDecimal trailingZeros = new BigDecimal("1.50");
        byte[] bytes = mapper.writeValueAsBytes(trailingZeros);
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes);
        assertEquals(1.5, unpacker.unpackDouble(), 0.0);

        // BigDecimal with precision beyond double range must throw
        BigDecimal tooHighPrecision = new BigDecimal("1.00000000000000000000000000000000000001");
        try {
            mapper.writeValueAsBytes(tooHighPrecision);
            assertTrue(false);
        }
        catch (IllegalArgumentException e) {
            assertTrue(true);
        }
    }

    @Test
    public void testEnableFeatureAutoCloseTarget()
            throws IOException
    {
        OutputStream out = createTempFileOutputStream();
        ObjectMapper objectMapper = new MessagePackMapper(new MessagePackFactory());
        List<Integer> integers = Arrays.asList(1);
        objectMapper.writeValue(out, integers);
        assertThrows(JacksonException.class, () -> {
            objectMapper.writeValue(out, integers);
        });
    }

    @Test
    public void testDisableFeatureAutoCloseTarget()
            throws Exception
    {
        File tempFile = createTempFile();
        OutputStream out = new FileOutputStream(tempFile);
        ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .build();
        List<Integer> integers = Arrays.asList(1);
        objectMapper.writeValue(out, integers);
        objectMapper.writeValue(out, integers);
        out.close();

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new FileInputStream(tempFile));
        assertEquals(1, unpacker.unpackArrayHeader());
        assertEquals(1, unpacker.unpackInt());
        assertEquals(1, unpacker.unpackArrayHeader());
        assertEquals(1, unpacker.unpackInt());
    }

    @Test
    public void testWritePrimitiveObjectViaObjectMapper()
            throws Exception
    {
        File tempFile = createTempFile();
        try (OutputStream out = Files.newOutputStream(tempFile.toPath())) {
            ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                    .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                    .build();
            objectMapper.writeValue(out, 1);
            objectMapper.writeValue(out, "two");
            objectMapper.writeValue(out, 3.14);
            objectMapper.writeValue(out, Arrays.asList(4));
            objectMapper.writeValue(out, 5L);
        }

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new FileInputStream(tempFile))) {
            assertEquals(1, unpacker.unpackInt());
            assertEquals("two", unpacker.unpackString());
            assertEquals(3.14, unpacker.unpackFloat(), 0.0001);
            assertEquals(1, unpacker.unpackArrayHeader());
            assertEquals(4, unpacker.unpackInt());
            assertEquals(5, unpacker.unpackLong());
        }
    }

    @Test
    public void testInMultiThreads()
            throws Exception
    {
        int threadCount = 8;
        final int loopCount = 4000;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        final ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .build();
        final List<ByteArrayOutputStream> buffers = new ArrayList<ByteArrayOutputStream>(threadCount);
        List<Future<Exception>> results = new ArrayList<Future<Exception>>();

        for (int ti = 0; ti < threadCount; ti++) {
            buffers.add(new ByteArrayOutputStream());
            final int threadIndex = ti;
            results.add(executorService.submit(new Callable<Exception>()
            {
                @Override
                public Exception call()
                        throws Exception
                {
                    try {
                        for (int i = 0; i < loopCount; i++) {
                            objectMapper.writeValue(buffers.get(threadIndex), threadIndex);
                        }
                        return null;
                    }
                    catch (Exception e) {
                        return e;
                    }
                }
            }));
        }

        for (int ti = 0; ti < threadCount; ti++) {
            Future<Exception> exceptionFuture = results.get(ti);
            Exception exception = exceptionFuture.get(20, TimeUnit.SECONDS);
            if (exception != null) {
                throw exception;
            }
            else {
                try (ByteArrayOutputStream outputStream = buffers.get(ti);
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(outputStream.toByteArray())) {
                    for (int i = 0; i < loopCount; i++) {
                        assertEquals(ti, unpacker.unpackInt());
                    }
                }
            }
        }
    }

    @Test
    public void testDisableStr8Support()
      throws Exception
    {
        String str8LengthString = new String(new char[32]).replace("\0", "a");

        ObjectMapper defaultMapper = new MessagePackMapper(new MessagePackFactory());
        byte[] resultWithStr8Format = defaultMapper.writeValueAsBytes(str8LengthString);
        assertEquals(resultWithStr8Format[0], MessagePack.Code.STR8);

        ObjectMapper mapperWithConfig = new MessagePackMapper(new MessagePackFactory().setStr8FormatSupport(false));
        byte[] resultWithoutStr8Format = mapperWithConfig.writeValueAsBytes(str8LengthString);
        assertNotEquals(resultWithoutStr8Format[0], MessagePack.Code.STR8);
    }

    interface NonStringKeyMapHolder
    {
        Map<Integer, String> getIntMap();

        void setIntMap(Map<Integer, String> intMap);

        Map<Long, String> getLongMap();

        void setLongMap(Map<Long, String> longMap);

        Map<Float, String> getFloatMap();

        void setFloatMap(Map<Float, String> floatMap);

        Map<Double, String> getDoubleMap();

        void setDoubleMap(Map<Double, String> doubleMap);

        Map<BigInteger, String> getBigIntMap();

        void setBigIntMap(Map<BigInteger, String> doubleMap);
    }

    public static class NonStringKeyMapHolderWithAnnotation
            implements NonStringKeyMapHolder
    {
        @JsonSerialize(keyUsing = MessagePackKeySerializer.class)
        private Map<Integer, String> intMap = new HashMap<Integer, String>();

        @JsonSerialize(keyUsing = MessagePackKeySerializer.class)
        private Map<Long, String> longMap = new HashMap<Long, String>();

        @JsonSerialize(keyUsing = MessagePackKeySerializer.class)
        private Map<Float, String> floatMap = new HashMap<Float, String>();

        @JsonSerialize(keyUsing = MessagePackKeySerializer.class)
        private Map<Double, String> doubleMap = new HashMap<Double, String>();

        @JsonSerialize(keyUsing = MessagePackKeySerializer.class)
        private Map<BigInteger, String> bigIntMap = new HashMap<BigInteger, String>();

        @Override
        public Map<Integer, String> getIntMap()
        {
            return intMap;
        }

        @Override
        public void setIntMap(Map<Integer, String> intMap)
        {
            this.intMap = intMap;
        }

        @Override
        public Map<Long, String> getLongMap()
        {
            return longMap;
        }

        @Override
        public void setLongMap(Map<Long, String> longMap)
        {
            this.longMap = longMap;
        }

        @Override
        public Map<Float, String> getFloatMap()
        {
            return floatMap;
        }

        @Override
        public void setFloatMap(Map<Float, String> floatMap)
        {
            this.floatMap = floatMap;
        }

        @Override
        public Map<Double, String> getDoubleMap()
        {
            return doubleMap;
        }

        @Override
        public void setDoubleMap(Map<Double, String> doubleMap)
        {
            this.doubleMap = doubleMap;
        }

        @Override
        public Map<BigInteger, String> getBigIntMap()
        {
            return bigIntMap;
        }

        @Override
        public void setBigIntMap(Map<BigInteger, String> bigIntMap)
        {
            this.bigIntMap = bigIntMap;
        }
    }

    public static class NonStringKeyMapHolderWithoutAnnotation
            implements NonStringKeyMapHolder
    {
        private Map<Integer, String> intMap = new HashMap<Integer, String>();

        private Map<Long, String> longMap = new HashMap<Long, String>();

        private Map<Float, String> floatMap = new HashMap<Float, String>();

        private Map<Double, String> doubleMap = new HashMap<Double, String>();

        private Map<BigInteger, String> bigIntMap = new HashMap<BigInteger, String>();

        @Override
        public Map<Integer, String> getIntMap()
        {
            return intMap;
        }

        @Override
        public void setIntMap(Map<Integer, String> intMap)
        {
            this.intMap = intMap;
        }

        @Override
        public Map<Long, String> getLongMap()
        {
            return longMap;
        }

        @Override
        public void setLongMap(Map<Long, String> longMap)
        {
            this.longMap = longMap;
        }

        @Override
        public Map<Float, String> getFloatMap()
        {
            return floatMap;
        }

        @Override
        public void setFloatMap(Map<Float, String> floatMap)
        {
            this.floatMap = floatMap;
        }

        @Override
        public Map<Double, String> getDoubleMap()
        {
            return doubleMap;
        }

        @Override
        public void setDoubleMap(Map<Double, String> doubleMap)
        {
            this.doubleMap = doubleMap;
        }

        @Override
        public Map<BigInteger, String> getBigIntMap()
        {
            return bigIntMap;
        }

        @Override
        public void setBigIntMap(Map<BigInteger, String> bigIntMap)
        {
            this.bigIntMap = bigIntMap;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testNonStringKey()
            throws Exception
    {
        for (Class<? extends NonStringKeyMapHolder> clazz :
                Arrays.asList(
                        NonStringKeyMapHolderWithAnnotation.class,
                        NonStringKeyMapHolderWithoutAnnotation.class)) {
            NonStringKeyMapHolder mapHolder = clazz.getConstructor().newInstance();
            mapHolder.getIntMap().put(Integer.MAX_VALUE, "i");
            mapHolder.getLongMap().put(Long.MIN_VALUE, "l");
            mapHolder.getFloatMap().put(Float.MAX_VALUE, "f");
            mapHolder.getDoubleMap().put(Double.MIN_VALUE, "d");
            mapHolder.getBigIntMap().put(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), "bi");

            ObjectMapper objectMapper;
            if (mapHolder instanceof NonStringKeyMapHolderWithoutAnnotation) {
                SimpleModule mod = new SimpleModule("test");
                mod.addKeySerializer(Object.class, new MessagePackKeySerializer());
                objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                        .addModule(mod)
                        .build();
            }
            else {
                objectMapper = new MessagePackMapper(new MessagePackFactory());
            }

            byte[] bytes = objectMapper.writeValueAsBytes(mapHolder);
            MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes);
            assertEquals(5, unpacker.unpackMapHeader());
            for (int i = 0; i < 5; i++) {
                String keyName = unpacker.unpackString();
                assertThat(unpacker.unpackMapHeader(), is(1));
                if (keyName.equals("intMap")) {
                    assertThat(unpacker.unpackInt(), is(Integer.MAX_VALUE));
                    assertThat(unpacker.unpackString(), is("i"));
                }
                else if (keyName.equals("longMap")) {
                    assertThat(unpacker.unpackLong(), is(Long.MIN_VALUE));
                    assertThat(unpacker.unpackString(), is("l"));
                }
                else if (keyName.equals("floatMap")) {
                    assertThat(unpacker.unpackFloat(), is(Float.MAX_VALUE));
                    assertThat(unpacker.unpackString(), is("f"));
                }
                else if (keyName.equals("doubleMap")) {
                    assertThat(unpacker.unpackDouble(), is(Double.MIN_VALUE));
                    assertThat(unpacker.unpackString(), is("d"));
                }
                else if (keyName.equals("bigIntMap")) {
                    assertThat(unpacker.unpackBigInteger(), is(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
                    assertThat(unpacker.unpackString(), is("bi"));
                }
                else {
                    fail("Unexpected key name: " + keyName);
                }
            }
        }
    }

    @Test
    public void testComplexTypeKey()
            throws IOException
    {
        HashMap<TinyPojo, Integer> map = new HashMap<TinyPojo, Integer>();
        TinyPojo pojo = new TinyPojo();
        pojo.t = "foo";
        map.put(pojo, 42);

        SimpleModule mod = new SimpleModule("test");
        mod.addKeySerializer(TinyPojo.class, new MessagePackKeySerializer());
        ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .addModule(mod)
                .build();
        byte[] bytes = objectMapper.writeValueAsBytes(map);

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes);
        assertThat(unpacker.unpackMapHeader(), is(1));
        assertThat(unpacker.unpackMapHeader(), is(1));
        assertThat(unpacker.unpackString(), is("t"));
        assertThat(unpacker.unpackString(), is("foo"));
        assertThat(unpacker.unpackInt(), is(42));
    }

    @Test
    public void testComplexTypeKeyWithV06Format()
            throws IOException
    {
        HashMap<TinyPojo, Integer> map = new HashMap<TinyPojo, Integer>();
        TinyPojo pojo = new TinyPojo();
        pojo.t = "foo";
        map.put(pojo, 42);

        SimpleModule mod = new SimpleModule("test");
        mod.addKeySerializer(TinyPojo.class, new MessagePackKeySerializer());
        ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .annotationIntrospector(new JsonArrayFormat())
                .addModule(mod)
                .build();
        byte[] bytes = objectMapper.writeValueAsBytes(map);

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes);
        assertThat(unpacker.unpackMapHeader(), is(1));
        assertThat(unpacker.unpackArrayHeader(), is(1));
        assertThat(unpacker.unpackString(), is("foo"));
        assertThat(unpacker.unpackInt(), is(42));
    }

    public static class IntegerSerializerStoringAsString
            extends ValueSerializer<Integer>
    {
        @Override
        public void serialize(Integer value, JsonGenerator gen, SerializationContext serializers)
                throws JacksonException
        {
            gen.writeNumber(String.valueOf(value));
        }
    }

    @Test
    public void serializeStringAsInteger()
            throws IOException
    {
        ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .addModule(new SimpleModule().addSerializer(Integer.class, new IntegerSerializerStoringAsString()))
                .build();

        assertThat(
            MessagePack.newDefaultUnpacker(objectMapper.writeValueAsBytes(Integer.MAX_VALUE)).unpackInt(),
                is(Integer.MAX_VALUE));
    }

    public static class LongSerializerStoringAsString
            extends ValueSerializer<Long>
    {
        @Override
        public void serialize(Long value, JsonGenerator gen, SerializationContext serializers)
                throws JacksonException
        {
            gen.writeNumber(String.valueOf(value));
        }
    }

    @Test
    public void serializeStringAsLong()
            throws IOException
    {
        ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .addModule(new SimpleModule().addSerializer(Long.class, new LongSerializerStoringAsString()))
                .build();

        assertThat(
            MessagePack.newDefaultUnpacker(objectMapper.writeValueAsBytes(Long.MIN_VALUE)).unpackLong(),
                is(Long.MIN_VALUE));
    }

    public static class FloatSerializerStoringAsString
            extends ValueSerializer<Float>
    {
        @Override
        public void serialize(Float value, JsonGenerator gen, SerializationContext serializers)
                throws JacksonException
        {
            gen.writeNumber(String.valueOf(value));
        }
    }

    @Test
    public void serializeStringAsFloat()
            throws IOException
    {
        ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .addModule(new SimpleModule().addSerializer(Float.class, new FloatSerializerStoringAsString()))
                .build();

        assertThat(
            MessagePack.newDefaultUnpacker(objectMapper.writeValueAsBytes(Float.MAX_VALUE)).unpackFloat(),
                is(Float.MAX_VALUE));
    }

    public static class DoubleSerializerStoringAsString
            extends ValueSerializer<Double>
    {
        @Override
        public void serialize(Double value, JsonGenerator gen, SerializationContext serializers)
                throws JacksonException
        {
            gen.writeNumber(String.valueOf(value));
        }
    }

    @Test
    public void serializeStringAsDouble()
            throws IOException
    {
        ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .addModule(new SimpleModule().addSerializer(Double.class, new DoubleSerializerStoringAsString()))
                .build();

        assertThat(
            MessagePack.newDefaultUnpacker(objectMapper.writeValueAsBytes(Double.MIN_VALUE)).unpackDouble(),
                is(Double.MIN_VALUE));
    }

   public static class BigDecimalSerializerStoringAsString
            extends ValueSerializer<BigDecimal>
    {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext serializers)
                throws JacksonException
        {
            gen.writeNumber(String.valueOf(value));
        }
    }

    @Test
    public void serializeStringAsBigDecimal()
            throws IOException
    {
        ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .addModule(new SimpleModule().addSerializer(BigDecimal.class, new BigDecimalSerializerStoringAsString()))
                .build();

        BigDecimal bd = BigDecimal.valueOf(Long.MAX_VALUE).add(BigDecimal.ONE);
        assertThat(
            MessagePack.newDefaultUnpacker(objectMapper.writeValueAsBytes(bd)).unpackBigInteger(),
                is(bd.toBigIntegerExact()));
    }

    public static class BigIntegerSerializerStoringAsString
            extends ValueSerializer<BigInteger>
    {
        @Override
        public void serialize(BigInteger value, JsonGenerator gen, SerializationContext serializers)
                throws JacksonException
        {
            gen.writeNumber(String.valueOf(value));
        }
    }

    @Test
    public void serializeStringAsBigInteger()
            throws IOException
    {
        ObjectMapper objectMapper = MessagePackMapper.builder(new MessagePackFactory())
                .addModule(new SimpleModule().addSerializer(BigInteger.class, new BigIntegerSerializerStoringAsString()))
                .build();

        BigInteger bi = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        assertThat(
            MessagePack.newDefaultUnpacker(objectMapper.writeValueAsBytes(bi)).unpackBigInteger(),
                is(bi));
    }

    @Test
    public void testNestedSerialization() throws Exception
    {
        ObjectMapper objectMapper = new MessagePackMapper(new MessagePackFactory());
        OuterClass outerClass = objectMapper.readValue(
                objectMapper.writeValueAsBytes(new OuterClass("Foo")),
                OuterClass.class);
        assertEquals("Foo", outerClass.getName());
    }

    static class OuterClass
    {
        private final String name;

        public OuterClass(@JsonProperty("name") String name)
        {
            this.name = name;
        }

        public String getName()
        {
            ObjectMapper objectMapper = new MessagePackMapper(new MessagePackFactory());
            InnerClass innerClass = objectMapper.readValue(
                    objectMapper.writeValueAsBytes(new InnerClass("Bar")),
                    InnerClass.class);
            assertEquals("Bar", innerClass.getName());

            return name;
        }
    }

    static class InnerClass
    {
        private final String name;

        public InnerClass(@JsonProperty("name") String name)
        {
            this.name = name;
        }

        public String getName()
        {
            return name;
        }
    }

    @Test
    public void testIsClosedAfterClose()
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        assertFalse(generator.isClosed());
        generator.writeStartArray();
        generator.writeEndArray();
        generator.close();
        assertTrue(generator.isClosed());
    }

    @Test
    public void testGeneratorReusableAfterRootContainerClose()
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartArray();
        generator.writeNumber(1);
        generator.writeEndArray();
        generator.flush();

        // Write a second root value; currentState must have reset to IN_ROOT
        generator.writeStartObject();
        generator.writeName("k");
        generator.writeNumber(2);
        generator.writeEndObject();
        generator.close();

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(baos.toByteArray());
        assertEquals(1, unpacker.unpackArrayHeader());
        assertEquals(1, unpacker.unpackInt());
        assertEquals(1, unpacker.unpackMapHeader());
        assertEquals("k", unpacker.unpackString());
        assertEquals(2, unpacker.unpackInt());
    }

    @Test
    public void testWriteRawStringWithOffset()
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartArray();
        generator.writeRaw("XXhelloXX", 2, 5); // "hello"
        generator.writeEndArray();
        generator.close();

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(baos.toByteArray());
        unpacker.unpackArrayHeader();
        assertEquals("hello", unpacker.unpackString());
    }

    @Test
    public void testWriteStringCharArrayWithOffset()
            throws IOException
    {
        // Padding chars before/after the actual content to test non-zero offset
        char[] buf = new char[] {'X', 'X', 'h', 'e', 'l', 'l', 'o', 'X'};
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartArray();
        generator.writeString(buf, 2, 5); // "hello"
        generator.writeEndArray();
        generator.close();

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(baos.toByteArray());
        unpacker.unpackArrayHeader();
        assertEquals("hello", unpacker.unpackString());
    }

    @Test
    public void testWriteStringCharArrayWithOffsetNonAscii()
            throws IOException
    {
        // Non-ASCII to exercise the non-fast-path in getBytesIfAscii
        char[] buf = new char[] {'X', '三', '四', '五', 'X'}; // 三四五
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartArray();
        generator.writeString(buf, 1, 3);
        generator.writeEndArray();
        generator.close();

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(baos.toByteArray());
        unpacker.unpackArrayHeader();
        assertEquals("三四五", unpacker.unpackString());
    }

    @Test
    public void testWriteUTF8StringWithOffset()
            throws IOException
    {
        // Padding bytes before/after to test non-zero offset in writeUTF8String
        byte[] buf = new byte[] {'X', 'X', 'h', 'i', 'X'};
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartArray();
        generator.writeUTF8String(buf, 2, 2); // "hi"
        generator.writeEndArray();
        generator.close();

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(baos.toByteArray());
        unpacker.unpackArrayHeader();
        assertEquals("hi", unpacker.unpackString());
    }

    @Test
    public void testWriteBinaryWithOffset()
            throws IOException
    {
        byte[] data = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04};
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartArray();
        generator.writeBinary(data, 1, 3); // bytes 0x01, 0x02, 0x03
        generator.writeEndArray();
        generator.close();

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(baos.toByteArray());
        unpacker.unpackArrayHeader();
        byte[] result = unpacker.readPayload(unpacker.unpackBinaryHeader());
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03}, result);
    }

    @Test
    public void testWriteBinaryByteBufferWithOffset()
            throws IOException
    {
        byte[] data = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04};
        ByteBuffer bb = ByteBuffer.wrap(data, 1, 3); // position=1, limit=4, remaining=3

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartArray();
        ObjectMapper mapper = new MessagePackMapper(factory);
        mapper.writeValue(generator, bb);
        generator.writeEndArray();
        generator.close();

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(baos.toByteArray());
        unpacker.unpackArrayHeader();
        byte[] result = unpacker.readPayload(unpacker.unpackBinaryHeader());
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03}, result);
    }

    @Test
    public void testStreamWriteContext()
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);

        TokenStreamContext ctx = generator.streamWriteContext();
        assertNotEquals(null, ctx);
        assertTrue(ctx.inRoot());

        generator.writeStartArray();
        ctx = generator.streamWriteContext();
        assertTrue(ctx.inArray());

        generator.writeStartObject();
        ctx = generator.streamWriteContext();
        assertTrue(ctx.inObject());

        generator.writeName("k");
        assertEquals("k", ctx.currentName());

        generator.writeNumber(1);
        generator.writeEndObject();
        ctx = generator.streamWriteContext();
        assertTrue(ctx.inArray());

        generator.writeEndArray();
        ctx = generator.streamWriteContext();
        assertTrue(ctx.inRoot());

        generator.close();
    }

    @Test
    public void testCurrentValue()
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);

        Object pojo = new Object();
        generator.writeStartObject(pojo);
        assertEquals(pojo, generator.currentValue());
        generator.writeName("k");
        generator.writeNumber(1);
        generator.writeEndObject();
        generator.close();
    }

    private static byte[] generate(MessagePackFactory factory, java.util.function.Consumer<JsonGenerator> body)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = factory.createGenerator(ObjectWriteContext.empty(), out)) {
            body.accept(gen);
        }
        return out.toByteArray();
    }

    @Test
    public void sizeHintProducesSameBytesAsNoHint()
    {
        int[] sizes = {0, 1, 15, 16, 300, 70_000};
        for (int size : sizes) {
            byte[] unhinted = generate(new MessagePackFactory(), gen -> {
                gen.writeStartArray();
                for (int i = 0; i < size; i++) {
                    gen.writeNumber(i);
                }
                gen.writeEndArray();
            });
            byte[] hinted = generate(new MessagePackFactory(), gen -> {
                gen.writeStartArray(null, size);
                for (int i = 0; i < size; i++) {
                    gen.writeNumber(i);
                }
                gen.writeEndArray();
            });
            assertArrayEquals(unhinted, hinted, "size " + size);

            byte[] unhintedObject = generate(new MessagePackFactory(), gen -> {
                gen.writeStartObject();
                for (int i = 0; i < size; i++) {
                    gen.writeName("k" + i);
                    gen.writeNumber(i);
                }
                gen.writeEndObject();
            });
            byte[] hintedObject = generate(new MessagePackFactory(), gen -> {
                gen.writeStartObject(null, size);
                for (int i = 0; i < size; i++) {
                    gen.writeName("k" + i);
                    gen.writeNumber(i);
                }
                gen.writeEndObject();
            });
            assertArrayEquals(unhintedObject, hintedObject, "size " + size);
        }
    }

    @Test
    public void wrongSizeHintIsCorrectedOnClose() throws IOException
    {
        // Hints that reserve a shorter and a longer header than the real count needs.
        int[][] cases = {{3, 20}, {20, 3}, {0, 70_000}, {70_000, 0}, {16, 15}};
        for (int[] c : cases) {
            int hint = c[0];
            int actual = c[1];
            byte[] bytes = generate(new MessagePackFactory(), gen -> {
                gen.writeStartArray(null, hint);
                for (int i = 0; i < actual; i++) {
                    gen.writeNull();
                }
                gen.writeEndArray();
            });
            try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
                assertEquals(actual, unpacker.unpackArrayHeader(), "hint " + hint);
                for (int i = 0; i < actual; i++) {
                    unpacker.unpackNil();
                }
                assertFalse(unpacker.hasNext());
            }
        }
    }

    @Test
    public void closingWithOpenContainersFinishesThemByDefault() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator gen = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), out);
        gen.writeStartObject();
        gen.writeName("a");
        gen.writeStartArray();
        gen.writeNumber(1);
        gen.close();

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(out.toByteArray())) {
            assertEquals(1, unpacker.unpackMapHeader());
            assertEquals("a", unpacker.unpackString());
            assertEquals(1, unpacker.unpackArrayHeader());
            assertEquals(1, unpacker.unpackInt());
            assertFalse(unpacker.hasNext());
        }
    }

    @Test
    public void closingWithOpenContainersWritesNothingWhenAutoCloseContentIsOff()
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessagePackFactory factory = (MessagePackFactory) new MessagePackFactory().rebuild()
                .disable(StreamWriteFeature.AUTO_CLOSE_CONTENT)
                .build();
        JsonGenerator gen = factory.createGenerator(ObjectWriteContext.empty(), out);
        gen.writeNumber(42);
        gen.writeStartObject();
        gen.writeName("a");
        gen.writeNumber(1);
        gen.close();

        // The complete root value before the unfinished one is kept; the unfinished one is dropped.
        assertArrayEquals(new byte[] {42}, out.toByteArray());
    }

    @Test
    public void nestingDeeperThanTheConstraintFails()
    {
        MessagePackFactory factory = (MessagePackFactory) new MessagePackFactory().rebuild()
                .streamWriteConstraints(tools.jackson.core.StreamWriteConstraints.builder().maxNestingDepth(10).build())
                .build();
        assertThrows(tools.jackson.core.exc.StreamConstraintsException.class, () -> generate(factory, gen -> {
            for (int i = 0; i < 11; i++) {
                gen.writeStartArray();
            }
        }));
        // Right at the limit is fine.
        generate(factory, gen -> {
            for (int i = 0; i < 10; i++) {
                gen.writeStartArray();
            }
            for (int i = 0; i < 10; i++) {
                gen.writeEndArray();
            }
        });
    }

    @Test
    public void closingAfterNestingConstraintFailureWritesTheOpenContainers()
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessagePackFactory factory = (MessagePackFactory) new MessagePackFactory().rebuild()
                .streamWriteConstraints(tools.jackson.core.StreamWriteConstraints.builder().maxNestingDepth(2).build())
                .build();
        JsonGenerator gen = factory.createGenerator(ObjectWriteContext.empty(), out);
        gen.writeStartArray();
        gen.writeStartArray();
        assertThrows(tools.jackson.core.exc.StreamConstraintsException.class, gen::writeStartArray);
        // The rejected container was never opened, so the write context is still its parent.
        assertEquals(2, gen.streamWriteContext().getNestingDepth());
        gen.close();

        // AUTO_CLOSE_CONTENT finishes the two containers that were actually opened.
        assertArrayEquals(new byte[] {(byte) 0x91, (byte) 0x90}, out.toByteArray());
    }

    @Test
    public void closingWithDanglingPropertyNameWritesNilForItsValue()
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator gen = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), out);
        gen.writeStartArray();
        gen.writeStartObject();
        gen.writeName("a");
        gen.writeNumber(1);
        gen.writeName("b");
        gen.close();

        // {"a": 1, "b": nil} inside the array, so the output is still valid MessagePack.
        assertArrayEquals(new byte[] {(byte) 0x91, (byte) 0x82, (byte) 0xa1, 'a', 1, (byte) 0xa1, 'b', (byte) 0xc0},
                out.toByteArray());
    }

    @Test
    public void explicitEndObjectWithDanglingPropertyNameFails()
    {
        JsonGenerator gen = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), new ByteArrayOutputStream());
        gen.writeStartObject();
        gen.writeName("a");
        assertThrows(tools.jackson.core.exc.StreamWriteException.class, gen::writeEndObject);
    }

    @Test
    public void bufferedByteCountIsReported()
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator gen = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), out);
        assertEquals(0, gen.streamWriteOutputBuffered());
        gen.writeStartArray();
        gen.writeString("hello");
        assertEquals(1 + 1 + 5, gen.streamWriteOutputBuffered());
        gen.writeEndArray();
        gen.flush();
        assertEquals(0, gen.streamWriteOutputBuffered());
        gen.close();
    }

    static class KeyWithList
    {
        public String name;
        public List<Integer> values;

        KeyWithList(String name, List<Integer> values)
        {
            this.name = name;
            this.values = values;
        }
    }

    @Test
    public void complexKeyCountsTowardsTheNestingLimit()
    {
        // Outer map (1), the key object (2), the key's list (3).
        SimpleModule mod = new SimpleModule("test");
        mod.addKeySerializer(KeyWithList.class, new MessagePackKeySerializer());
        Map<KeyWithList, Integer> map = Collections.singletonMap(new KeyWithList("k", Arrays.asList(1)), 1);

        ObjectMapper limitedToTwo = MessagePackMapper.builder(withMaxNestingDepth(2)).addModule(mod).build();
        assertThrows(tools.jackson.core.exc.StreamConstraintsException.class, () -> limitedToTwo.writeValueAsBytes(map));

        ObjectMapper limitedToThree = MessagePackMapper.builder(withMaxNestingDepth(3)).addModule(mod).build();
        byte[] bytes = limitedToThree.writeValueAsBytes(map);
        assertEquals((byte) 0x81, bytes[0]);
    }

    private static MessagePackFactory withMaxNestingDepth(int depth)
    {
        return (MessagePackFactory) new MessagePackFactory().rebuild()
                .streamWriteConstraints(tools.jackson.core.StreamWriteConstraints.builder().maxNestingDepth(depth).build())
                .build();
    }

    @Test
    public void complexKeyWithNestedContainerIsWrittenInPlace() throws IOException
    {
        // The key's own containers get patched inside the parent's buffer, between the
        // parent map's reserved header and the value that follows.
        SimpleModule mod = new SimpleModule("test");
        mod.addKeySerializer(KeyWithList.class, new MessagePackKeySerializer());
        ObjectMapper mapper = MessagePackMapper.builder(new MessagePackFactory()).addModule(mod).build();

        Map<KeyWithList, List<String>> map = new java.util.LinkedHashMap<>();
        map.put(new KeyWithList("first", Arrays.asList(1, 2, 3)), Arrays.asList("a", "b"));
        map.put(new KeyWithList("second", java.util.Collections.emptyList()), Arrays.asList("c"));
        byte[] bytes = mapper.writeValueAsBytes(map);

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
            assertEquals(2, unpacker.unpackMapHeader());

            assertEquals(2, unpacker.unpackMapHeader());
            assertEquals("name", unpacker.unpackString());
            assertEquals("first", unpacker.unpackString());
            assertEquals("values", unpacker.unpackString());
            assertEquals(3, unpacker.unpackArrayHeader());
            assertEquals(1, unpacker.unpackInt());
            assertEquals(2, unpacker.unpackInt());
            assertEquals(3, unpacker.unpackInt());
            assertEquals(2, unpacker.unpackArrayHeader());
            assertEquals("a", unpacker.unpackString());
            assertEquals("b", unpacker.unpackString());

            assertEquals(2, unpacker.unpackMapHeader());
            assertEquals("name", unpacker.unpackString());
            assertEquals("second", unpacker.unpackString());
            assertEquals("values", unpacker.unpackString());
            assertEquals(0, unpacker.unpackArrayHeader());
            assertEquals(1, unpacker.unpackArrayHeader());
            assertEquals("c", unpacker.unpackString());

            assertFalse(unpacker.hasNext());
        }
    }

    @Test
    public void testVersion()
    {
        assertNotEquals(null, factory.version());
        assertEquals("org.komamitsu", factory.version().getGroupId());
        assertEquals("jackson-dataformat-msgpack", factory.version().getArtifactId());
    }

    @Test
    public void testSerializedStringMethods() throws IOException
    {
        MessagePackSerializedString s = new MessagePackSerializedString("hello");

        byte[] utf8Target = new byte[10];
        int written = s.appendUnquotedUTF8(utf8Target, 2);
        assertEquals(5, written);
        assertArrayEquals(new byte[] {'h', 'e', 'l', 'l', 'o'}, Arrays.copyOfRange(utf8Target, 2, 7));

        char[] charTarget = new char[10];
        written = s.appendUnquoted(charTarget, 3);
        assertEquals(5, written);
        assertEquals("hello", new String(charTarget, 3, 5));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        written = s.writeUnquotedUTF8(baos);
        assertEquals(5, written);
        assertArrayEquals("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), baos.toByteArray());
    }

    // Regression: addValueNode must call writeContext.writeValue() so that the Jackson write
    // context resets _gotPropertyId after each value. Without it, the second writeName() in the
    // same object finds _gotPropertyId still set from the first writeName() and returns false
    // without updating currentName(), leaving streamWriteContext().currentName() stale.
    @Test
    public void testWriteContextCurrentNameIsUpdatedForEveryProperty()
            throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartObject();

        generator.writeName("alpha");
        generator.writeNumber(1);

        generator.writeName("beta");
        assertEquals("beta", generator.streamWriteContext().currentName());
        generator.writeNumber(2);

        generator.writeEndObject();
        generator.close();
    }

    // Regression: writePropertyId() must call writeContext.writeName() when supportIntegerKeys
    // is true. Without it, streamWriteContext() never learns a name was written, so currentName()
    // returns null and any downstream code relying on context state (e.g. duplicate-name
    // detection, error messages) sees wrong state.
    @Test
    public void testWritePropertyIdUpdatesWriteContext()
            throws IOException
    {
        MessagePackFactory intKeyFactory = new MessagePackFactory().setSupportIntegerKeys(true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = intKeyFactory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartObject();
        generator.writePropertyId(42L);
        assertEquals("42", generator.streamWriteContext().currentName());
        generator.writeString("value");
        generator.writeEndObject();
        generator.close();
    }

    @Test
    public void testNullSerializedStringKeyDoesNotThrowNpe()
            throws IOException
    {
        // writeName(MessagePackSerializedString(null)) calls getValue() → null.toString() → NPE.
        // A null key should be serialized as msgpack nil, not crash.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartObject();
        generator.writeName(new MessagePackSerializedString(null));
        generator.writeNumber(42);
        generator.writeEndObject();
        generator.close();

        // Verify the null key round-trips as PROPERTY_NAME with null current name
        try (JsonParser parser =
                new MessagePackFactory().createParser(ObjectReadContext.empty(), baos.toByteArray())) {
            assertEquals(JsonToken.START_OBJECT, parser.nextToken());
            assertEquals(JsonToken.PROPERTY_NAME, parser.nextToken());
            assertNull(parser.currentName());
            assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
            assertEquals(42, parser.getIntValue());
            assertEquals(JsonToken.END_OBJECT, parser.nextToken());
        }
    }

    @Test
    public void testFlushMidWriteOnSecondRootContainerDoesNotCorruptState()
            throws IOException
    {
        // After the first root container closes, isElementsClosed=true.
        // Opening a second root container does not reset this flag, so a
        // flush() call while the second container is still open will pack
        // the incomplete node tree and wipe nodes[], corrupting subsequent writes.
        MessagePackFactory factory = new MessagePackFactory();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);

        generator.writeStartArray();
        generator.writeNumber(1);
        generator.writeEndArray();

        generator.writeStartArray();        // second root — isElementsClosed still true
        generator.flush();                  // must NOT pack the incomplete second array
        generator.writeNumber(2);
        generator.writeEndArray();

        generator.close();

        ObjectMapper mapper = MessagePackMapper.builder(new MessagePackFactory())
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        try (JsonParser parser =
                new MessagePackFactory().createParser(ObjectReadContext.empty(), baos.toByteArray())) {
            List<Integer> first = mapper.readValue(parser, new TypeReference<List<Integer>>() {});
            assertEquals(Collections.singletonList(1), first);
            List<Integer> second = mapper.readValue(parser, new TypeReference<List<Integer>>() {});
            assertEquals(Collections.singletonList(2), second);
        }
    }

    @Test
    public void testRootScalarAfterClosedRootContainerPreservesOrder()
            throws IOException
    {
        // A root scalar written after a closed root container must be emitted AFTER
        // the container, not before. Without a fix, addValueNode() packs the scalar
        // immediately (default branch) while the container stays buffered, reversing order.
        MessagePackFactory factory = new MessagePackFactory();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartArray();
        generator.writeNumber(1);
        generator.writeEndArray();
        generator.writeNumber(2);  // root scalar — must come AFTER the array
        generator.close();

        ObjectMapper mapper = MessagePackMapper.builder(new MessagePackFactory())
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        try (JsonParser parser =
                new MessagePackFactory().createParser(ObjectReadContext.empty(), baos.toByteArray())) {
            List<Integer> list = mapper.readValue(parser, new TypeReference<List<Integer>>() {});
            assertEquals(Collections.singletonList(1), list);
            int scalar = mapper.readValue(parser, Integer.class);
            assertEquals(2, scalar);
        }
    }

    // Bug: writeNumber(String) is missing "return this" after addValueNode(d) in the
    // NaN/Infinity fallback branch.  addValueNode() advances the write-context state
    // and (for root-level scalars) writes bytes to the output stream before the
    // method falls through to "throw new NumberFormatException(encodedValue)".
    @Test
    public void testWriteNumberStringNanAndInfinity() throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Avoid try-with-resources: if writeNumber throws, the implicit close
        // flushes a half-written node list and masks the original failure.
        JsonGenerator gen = factory.createGenerator(ObjectWriteContext.empty(), baos);
        gen.writeStartArray();
        gen.writeNumber("NaN");       // Bug: NFE thrown after state mutation
        gen.writeNumber("Infinity");
        gen.writeNumber("-Infinity");
        gen.writeEndArray();
        gen.close();

        try (JsonParser p = factory.createParser(ObjectReadContext.empty(), baos.toByteArray())) {
            assertEquals(JsonToken.START_ARRAY, p.nextToken());
            assertEquals(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
            assertTrue(Double.isNaN(p.getDoubleValue()));
            assertEquals(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
            assertEquals(Double.POSITIVE_INFINITY, p.getDoubleValue(), 0);
            assertEquals(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
            assertEquals(Double.NEGATIVE_INFINITY, p.getDoubleValue(), 0);
            assertEquals(JsonToken.END_ARRAY, p.nextToken());
        }
    }

    // Bug: same DupDetector NPE as the read-path bug, on the write side.
    // writeName(SerializableString) calls writeContext.writeName(name.getValue())
    // where MessagePackSerializedString(null).getValue() == null.  With
    // STRICT_DUPLICATE_DETECTION enabled and a prior non-null key already seen,
    // DupDetector.isDup(null) reaches name.equals(_firstName) → NPE.
    @Test
    public void testNullKeyWithWriteDupDetectionDoesNotNPE() throws IOException
    {
        MessagePackFactory f = new MessagePackFactoryBuilder()
                .enable(StreamWriteFeature.STRICT_DUPLICATE_DETECTION)
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JsonGenerator gen = f.createGenerator(ObjectWriteContext.empty(), baos)) {
            gen.writeStartObject();
            gen.writeName("foo");
            gen.writeNumber(1);
            // MessagePackSerializedString(null).getValue() == null
            gen.writeName(new MessagePackSerializedString(null)); // Bug: NPE here
            gen.writeNumber(2);
            gen.writeEndObject();
        }
    }

    // Writes a complex map key as a value that is never finished, the way a serializer that
    // threw halfway would leave it.
    public static class UnfinishedSerializer
            extends ValueSerializer<TinyPojo>
    {
        @Override
        public void serialize(TinyPojo value, JsonGenerator gen, SerializationContext ctxt)
        {
            gen.writeStartObject();
        }
    }

    private static ObjectMapper mapperWithUnfinishedKey(boolean autoCloseContent)
    {
        SimpleModule mod = new SimpleModule("test");
        mod.addKeySerializer(TinyPojo.class, new MessagePackKeySerializer());
        mod.addSerializer(TinyPojo.class, new UnfinishedSerializer());
        return MessagePackMapper.builder(new MessagePackFactory())
                .configure(StreamWriteFeature.AUTO_CLOSE_CONTENT, autoCloseContent)
                .addModule(mod)
                .build();
    }

    private static HashMap<TinyPojo, Integer> mapWithPojoKey()
    {
        HashMap<TinyPojo, Integer> map = new HashMap<>();
        TinyPojo pojo = new TinyPojo();
        pojo.t = "foo";
        map.put(pojo, 42);
        return map;
    }

    // Writes nothing at all for the key, so no bytes reach the buffer.
    public static class SilentSerializer
            extends ValueSerializer<TinyPojo>
    {
        @Override
        public void serialize(TinyPojo value, JsonGenerator gen, SerializationContext ctxt)
        {
        }
    }

    // Reporting the missing key must not leave the map expecting a value for it. A caller that
    // catches the failure and closes the generator would otherwise get nil written for that
    // pending name, emitting a map whose one entry has a value and no key.
    @Test
    public void aKeyThatWritesNothingLeavesNoPendingEntry() throws IOException
    {
        SimpleModule mod = new SimpleModule("test");
        mod.addKeySerializer(TinyPojo.class, new MessagePackKeySerializer());
        mod.addSerializer(TinyPojo.class, new SilentSerializer());
        ObjectMapper mapper = MessagePackMapper.builder(new MessagePackFactory())
                .addModule(mod)
                .build();

        TinyPojo pojo = new TinyPojo();
        pojo.t = "foo";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = mapper.createGenerator(out)) {
            gen.writeStartObject();
            assertThrows(JacksonException.class,
                    () -> gen.writeName(new MessagePackSerializedString(pojo)));
        }

        // AUTO_CLOSE_CONTENT finished the map on close; it must be an empty one, not an entry
        // whose key never reached the buffer.
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(out.toByteArray())) {
            assertEquals(0, unpacker.unpackMapHeader());
            assertFalse(unpacker.hasNext());
        }
    }

    // With AUTO_CLOSE_CONTENT off the unfinished key is discarded, so writing the value would
    // leave a map entry with no key at all. That must be reported instead.
    @Test
    public void anAbandonedComplexKeyIsReported()
    {
        assertThrows(JacksonException.class,
                () -> mapperWithUnfinishedKey(false).writeValueAsBytes(mapWithPojoKey()));
    }

    // With AUTO_CLOSE_CONTENT on, the same key is completed as an empty map and the entry stands.
    @Test
    public void anUnfinishedComplexKeyIsCompletedWhenContentIsAutoClosed() throws IOException
    {
        byte[] bytes = mapperWithUnfinishedKey(true).writeValueAsBytes(mapWithPojoKey());
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
            assertEquals(1, unpacker.unpackMapHeader());
            assertEquals(0, unpacker.unpackMapHeader());
            assertEquals(42, unpacker.unpackInt());
            assertFalse(unpacker.hasNext());
        }
    }

    // A name the format cannot write must leave the context as it was. Otherwise the caller
    // catches the failure, closes the generator, and AUTO_CLOSE_CONTENT writes nil for a name
    // whose bytes never reached the buffer, producing a map entry with a value and no key.
    @Test
    public void aRejectedKeyLeavesNoPhantomName() throws IOException
    {
        BigInteger tooLarge = BigInteger.ONE.shiftLeft(64);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), out)) {
            gen.writeStartObject();
            gen.writeName("ok");
            gen.writeNumber(1);
            assertThrows(IllegalArgumentException.class,
                    () -> gen.writeName(new MessagePackSerializedString(tooLarge)));
        }

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(out.toByteArray())) {
            assertEquals(1, unpacker.unpackMapHeader());
            assertEquals("ok", unpacker.unpackString());
            assertEquals(1, unpacker.unpackInt());
            assertFalse(unpacker.hasNext());
        }
    }

    // Same for a name rejected by strict duplicate detection: no bytes were written, so the
    // context must not be left expecting a value for it.
    @Test
    public void aRejectedDuplicateNameLeavesNoPhantomName() throws IOException
    {
        MessagePackFactory f = new MessagePackFactoryBuilder()
                .enable(StreamWriteFeature.STRICT_DUPLICATE_DETECTION)
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = f.createGenerator(ObjectWriteContext.empty(), out)) {
            gen.writeStartObject();
            gen.writeName("dup");
            gen.writeNumber(1);
            assertThrows(StreamWriteException.class, () -> gen.writeName("dup"));
        }

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(out.toByteArray())) {
            assertEquals(1, unpacker.unpackMapHeader());
            assertEquals("dup", unpacker.unpackString());
            assertEquals(1, unpacker.unpackInt());
            assertFalse(unpacker.hasNext());
        }
    }

    // A slice outside the array must be rejected before anything is written or counted, so a
    // caller that catches the exception can carry on with a consistent stream.
    @Test
    public void invalidSliceLeavesNoTrace()
    {
        byte[] three = {1, 2, 3};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), out)) {
            gen.writeStartArray();
            assertThrows(IndexOutOfBoundsException.class, () -> gen.writeUTF8String(three, 0, 100));
            assertThrows(IndexOutOfBoundsException.class, () -> gen.writeUTF8String(three, 2, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> gen.writeUTF8String(three, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> gen.writeBinary(three, 0, 100));
            assertThrows(IndexOutOfBoundsException.class, () -> gen.writeBinary(three, 1, -1));
            gen.writeNumber(7);
            gen.writeEndArray();
        }
        assertArrayEquals(new byte[] {(byte) 0x91, 7}, out.toByteArray());
    }

    // An unrepresentable BigInteger or BigDecimal must be rejected before it is counted, for
    // the same reason as an invalid slice.
    @Test
    public void unrepresentableNumberLeavesNoTrace()
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), out)) {
            gen.writeStartArray();
            IllegalArgumentException tooBig = assertThrows(IllegalArgumentException.class,
                    () -> gen.writeNumber(BigInteger.ONE.shiftLeft(64)));
            IllegalArgumentException tooSmall = assertThrows(IllegalArgumentException.class,
                    () -> gen.writeNumber(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)));
            assertTrue(tooBig.getMessage().contains("-2^63") && tooBig.getMessage().contains("2^64"), tooBig.getMessage());
            assertEquals(tooBig.getMessage().replaceAll("[-0-9]+$", ""), tooSmall.getMessage().replaceAll("[-0-9]+$", ""));
            assertThrows(IllegalArgumentException.class, () -> gen.writeNumber(new BigDecimal("1234567890.98765432100")));
            gen.writeNumber(7);
            gen.writeEndArray();
        }
        assertArrayEquals(new byte[] {(byte) 0x91, 7}, out.toByteArray());
    }

    @Test
    public void writingAfterCloseIsReportedNotAnNpe()
    {
        JsonGenerator gen = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), new ByteArrayOutputStream());
        gen.writeNumber(1);
        gen.close();
        assertThrows(tools.jackson.core.exc.StreamWriteException.class, () -> gen.writeNumber(2));
        assertThrows(tools.jackson.core.exc.StreamWriteException.class, () -> gen.writeString("s"));
        assertThrows(tools.jackson.core.exc.StreamWriteException.class, gen::writeStartArray);
        assertThrows(tools.jackson.core.exc.StreamWriteException.class, gen::writeStartObject);
        assertThrows(tools.jackson.core.exc.StreamWriteException.class, () -> gen.writeName("n"));
    }

    // Jackson's contract: a null argument to these is written as a null token.
    @Test
    public void nullArgumentsAreWrittenAsNil()
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator gen = new MessagePackFactory().createGenerator(ObjectWriteContext.empty(), out)) {
            gen.writeStartArray();
            gen.writeString((String) null);
            gen.writeNumber((BigInteger) null);
            gen.writeNumber((BigDecimal) null);
            gen.writeNumber((String) null);
            gen.writeEndArray();
        }
        assertArrayEquals(new byte[] {(byte) 0x94, (byte) 0xc0, (byte) 0xc0, (byte) 0xc0, (byte) 0xc0}, out.toByteArray());
    }

    @Test
    public void testSecondNullKeyIsADuplicateUnderStrictDetection()
    {
        MessagePackFactory f = new MessagePackFactoryBuilder()
                .enable(StreamWriteFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        JsonGenerator gen = f.createGenerator(ObjectWriteContext.empty(), new ByteArrayOutputStream());
        gen.writeStartObject();
        gen.writeName(new MessagePackSerializedString(null));
        gen.writeNumber(1);
        assertThrows(tools.jackson.core.exc.StreamWriteException.class,
                () -> gen.writeName(new MessagePackSerializedString(null)));

        // A nil key in a fresh object is fine again.
        JsonGenerator gen2 = f.createGenerator(ObjectWriteContext.empty(), new ByteArrayOutputStream());
        gen2.writeStartArray();
        gen2.writeStartObject();
        gen2.writeName(new MessagePackSerializedString(null));
        gen2.writeNumber(1);
        gen2.writeEndObject();
        gen2.writeStartObject();
        gen2.writeName(new MessagePackSerializedString(null));
        gen2.writeNumber(2);
        gen2.writeEndObject();
        gen2.writeEndArray();
        gen2.close();
    }

    // Bug: MessagePackSerializedString.charLength() calls getValue().length()
    // unconditionally; getValue() returns null when value is null → NPE.
    @Test
    public void testSerializedStringNullValueCharLengthDoesNotNPE()
    {
        MessagePackSerializedString s = new MessagePackSerializedString(null);
        // Bug: null.length() → NullPointerException
        assertEquals(0, s.charLength());
    }

    @Test
    public void testMultipleRootContainersWithoutFlush()
            throws IOException
    {
        // Writing two consecutive root-level containers on the same generator without
        // an intervening flush() must not throw IndexOutOfBoundsException.
        // The second root container sits at node index 1, so the root check
        // "currentParentElementIndex == 0" incorrectly falls through to the
        // nested-container path and calls nodes.get(-1).
        MessagePackFactory factory = new MessagePackFactory();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), baos);
        generator.writeStartArray();
        generator.writeNumber(1);
        generator.writeEndArray();
        // second root container — no flush() in between
        generator.writeStartObject();
        generator.writeStringProperty("key", "value");
        generator.writeEndObject();
        generator.close();

        // Verify both values were written correctly by reading from a shared parser
        ObjectMapper mapper = MessagePackMapper.builder(new MessagePackFactory())
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        try (JsonParser parser =
                new MessagePackFactory().createParser(ObjectReadContext.empty(), baos.toByteArray())) {
            List<Integer> list = mapper.readValue(parser, new TypeReference<List<Integer>>() {});
            assertEquals(Collections.singletonList(1), list);
            Map<String, String> map = mapper.readValue(parser, new TypeReference<Map<String, String>>() {});
            assertEquals(Collections.singletonMap("key", "value"), map);
        }
    }
}
