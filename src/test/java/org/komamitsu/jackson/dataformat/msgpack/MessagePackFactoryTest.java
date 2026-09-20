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

import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.TSFBuilder;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;

public class MessagePackFactoryTest
        extends MessagePackDataformatTestBase
{
    @Test
    public void testCreateGenerator()
            throws IOException
    {
        JsonEncoding enc = JsonEncoding.UTF8;
        JsonGenerator generator = factory.createGenerator(ObjectWriteContext.empty(), out, enc);
        assertEquals(MessagePackGenerator.class, generator.getClass());
    }

    @Test
    public void testCreateParser()
            throws IOException
    {
        JsonParser parser = factory.createParser(ObjectReadContext.empty(), in);
        assertEquals(MessagePackParser.class, parser.getClass());
    }

    @Test
    public void testCopyWithDefaultConfig()
            throws IOException
    {
        MessagePackFactory messagePackFactory = new MessagePackFactory();
        ObjectMapper objectMapper = new MessagePackMapper(messagePackFactory);

        // Use the original ObjectMapper in advance
        {
            byte[] bytes = objectMapper.writeValueAsBytes(1234);
            assertThat(objectMapper.readValue(bytes, Integer.class), is(1234));
        }

        // Copy the factory
        TokenStreamFactory copiedFactory = messagePackFactory.copy();
        assertThat(copiedFactory, is(instanceOf(MessagePackFactory.class)));
        MessagePackFactory copiedMessagePackFactory = (MessagePackFactory) copiedFactory;

        assertThat(copiedMessagePackFactory.isStr8FormatSupport(), is(true));
        assertThat(copiedMessagePackFactory.getExtTypeCustomDesers(), is(nullValue()));

        // Check the copied factory works fine
        ObjectMapper copiedObjectMapper = new MessagePackMapper(copiedMessagePackFactory);
        Map<String, Integer> map = new HashMap<>();
        map.put("one", 1);
        Map<String, Integer> deserialized = copiedObjectMapper
            .readValue(objectMapper.writeValueAsBytes(map), new TypeReference<Map<String, Integer>>() {});
        assertThat(deserialized.size(), is(1));
        assertThat(deserialized.get("one"), is(1));
    }

    @Test
    public void testRebuildWithDefaultConfig()
            throws IOException
    {
        MessagePackFactory messagePackFactory = new MessagePackFactory();
        TSFBuilder<?, ?> builder = messagePackFactory.rebuild();
        assertThat(builder, is(instanceOf(MessagePackFactoryBuilder.class)));

        MessagePackFactory rebuilt = (MessagePackFactory) builder.build();
        assertThat(rebuilt, is(not(sameInstance(messagePackFactory))));
        assertThat(rebuilt.isStr8FormatSupport(), is(true));
        assertThat(rebuilt.getExtTypeCustomDesers(), is(nullValue()));

        ObjectMapper rebuiltObjectMapper = new MessagePackMapper(rebuilt);
        byte[] bytes = rebuiltObjectMapper.writeValueAsBytes(42);
        assertThat(rebuiltObjectMapper.readValue(bytes, Integer.class), is(42));
    }

    @Test
    public void testRebuildWithAdvancedConfig()
            throws IOException
    {
        ExtensionTypeCustomDeserializers extTypeCustomDesers = new ExtensionTypeCustomDeserializers();
        extTypeCustomDesers.addCustomDeser((byte) 42,
                new ExtensionTypeCustomDeserializers.Deser()
                {
                    @Override
                    public Object deserialize(byte[] data)
                            throws IOException
                    {
                        TinyPojo pojo = new TinyPojo();
                        pojo.t = new String(data);
                        return pojo;
                    }
                }
        );
        MessagePackFactory messagePackFactory = new MessagePackFactory().setStr8FormatSupport(false);
        messagePackFactory.setExtTypeCustomDesers(extTypeCustomDesers);

        MessagePackFactory rebuilt = (MessagePackFactory) messagePackFactory.rebuild().build();
        assertThat(rebuilt, is(not(sameInstance(messagePackFactory))));
        assertThat(rebuilt.isStr8FormatSupport(), is(false));
        assertThat(rebuilt.getExtTypeCustomDesers().getDeser((byte) 42), is(notNullValue()));
        assertThat(rebuilt.getExtTypeCustomDesers().getDeser((byte) 43), is(nullValue()));
    }

    @Test
    public void testSnapshotReturnsNewInstance()
    {
        MessagePackFactory messagePackFactory = new MessagePackFactory();
        TokenStreamFactory snapshot = messagePackFactory.snapshot();
        assertThat(snapshot, is(not(sameInstance(messagePackFactory))));
        assertThat(snapshot, is(instanceOf(MessagePackFactory.class)));
    }

    // The symbol table is transient, so a deserialized factory must get a fresh one.
    @Test
    public void testDeserializedFactoryCanParse() throws IOException, ClassNotFoundException
    {
        MessagePackFactory original = new MessagePackFactory().setStr8FormatSupport(false).setSupportIntegerKeys(true);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bytes)) {
            oos.writeObject(original);
        }
        MessagePackFactory restored;
        try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (MessagePackFactory) ois.readObject();
        }
        assertEquals(false, restored.isStr8FormatSupport());
        assertEquals(true, restored.isSupportIntegerKeys());

        byte[] map = {(byte) 0x81, (byte) 0xa1, 'k', 1};
        try (JsonParser p = restored.createParser(ObjectReadContext.empty(), map)) {
            assertEquals(tools.jackson.core.JsonToken.START_OBJECT, p.nextToken());
            assertEquals(tools.jackson.core.JsonToken.PROPERTY_NAME, p.nextToken());
            assertEquals("k", p.currentName());
        }
    }

    // Custom extension deserializers are part of the factory's configuration, so they must
    // survive Java serialization with it.
    @Test
    public void testDeserializedFactoryKeepsCustomExtensionDeserializers() throws IOException, ClassNotFoundException
    {
        ExtensionTypeCustomDeserializers desers = new ExtensionTypeCustomDeserializers();
        desers.addCustomDeser((byte) 7, data -> "ext:" + new String(data, java.nio.charset.StandardCharsets.UTF_8));
        MessagePackFactory original = new MessagePackFactory().setExtTypeCustomDesers(desers);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bytes)) {
            oos.writeObject(original);
        }
        MessagePackFactory restored;
        try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (MessagePackFactory) ois.readObject();
        }

        // fixext 2, type 7, payload "hi".
        byte[] input = {(byte) 0xd5, 7, 'h', 'i'};
        try (JsonParser p = restored.createParser(ObjectReadContext.empty(), input)) {
            assertEquals(tools.jackson.core.JsonToken.VALUE_EMBEDDED_OBJECT, p.nextToken());
            assertEquals("ext:hi", p.getEmbeddedObject());
        }
    }

    @Test
    public void testCopyWithAdvancedConfig()
            throws IOException
    {
        ExtensionTypeCustomDeserializers extTypeCustomDesers = new ExtensionTypeCustomDeserializers();
        extTypeCustomDesers.addCustomDeser((byte) 42,
                new ExtensionTypeCustomDeserializers.Deser()
                {
                    @Override
                    public Object deserialize(byte[] data)
                            throws IOException
                    {
                        TinyPojo pojo = new TinyPojo();
                        pojo.t = new String(data);
                        return pojo;
                    }
                }
        );

        MessagePackFactory messagePackFactory = new MessagePackFactory().setStr8FormatSupport(false);
        messagePackFactory.setExtTypeCustomDesers(extTypeCustomDesers);

        ObjectMapper objectMapper = new MessagePackMapper(messagePackFactory);

        // Use the original ObjectMapper in advance
        {
            byte[] bytes = objectMapper.writeValueAsBytes(1234);
            assertThat(objectMapper.readValue(bytes, Integer.class), is(1234));
        }

        // Copy the factory
        TokenStreamFactory copiedFactory = messagePackFactory.copy();
        assertThat(copiedFactory, is(instanceOf(MessagePackFactory.class)));
        MessagePackFactory copiedMessagePackFactory = (MessagePackFactory) copiedFactory;

        assertThat(copiedMessagePackFactory.isStr8FormatSupport(), is(false));
        assertThat(copiedMessagePackFactory.getExtTypeCustomDesers().getDeser((byte) 42), is(notNullValue()));
        assertThat(copiedMessagePackFactory.getExtTypeCustomDesers().getDeser((byte) 43), is(nullValue()));

        // Check the copied factory works fine
        ObjectMapper copiedObjectMapper = new MessagePackMapper(copiedMessagePackFactory);
        Map<String, Integer> map = new HashMap<>();
        map.put("one", 1);
        Map<String, Integer> deserialized = copiedObjectMapper
            .readValue(objectMapper.writeValueAsBytes(map), new TypeReference<Map<String, Integer>>() {});
        assertThat(deserialized.size(), is(1));
        assertThat(deserialized.get("one"), is(1));
    }
}
