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
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A serializer or deserializer that runs the same ObjectMapper again, on the same thread,
 * while the outer generator or parser is mid-value. Earlier versions kept per-thread state
 * that the inner call could clobber, so the outer value came out wrong.
 */
public class NestedUsageTest
{
    // Set once the mapper is built, since the (de)serializers need the mapper they are registered on.
    private static ObjectMapper mapper;

    static final class Payload
    {
        public final String text;
        public final long number;

        Payload(@JsonProperty("text") String text, @JsonProperty("number") long number)
        {
            this.text = text;
            this.number = number;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Payload && ((Payload) o).text.equals(text) && ((Payload) o).number == number;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(text, number);
        }
    }

    // Serialized by Embedded(De)Serializer as a binary value holding a nested MessagePack document.
    static final class Embedded
    {
        final Payload payload;

        Embedded(Payload payload)
        {
            this.payload = payload;
        }
    }

    static final class Outer
    {
        public final String before;
        public final Embedded embedded;
        public final String after;
        public final List<Integer> numbers;

        Outer(@JsonProperty("before") String before, @JsonProperty("embedded") Embedded embedded,
                @JsonProperty("after") String after, @JsonProperty("numbers") List<Integer> numbers)
        {
            this.before = before;
            this.embedded = embedded;
            this.after = after;
            this.numbers = numbers;
        }
    }

    static final class EmbeddedSerializer extends StdSerializer<Embedded>
    {
        EmbeddedSerializer()
        {
            super(Embedded.class);
        }

        @Override
        public void serialize(Embedded value, JsonGenerator gen, SerializationContext ctxt)
        {
            // Re-entrant use of the same mapper while the outer generator is in the middle of an object.
            gen.writeBinary(mapper.writeValueAsBytes(value.payload));
        }
    }

    static final class EmbeddedDeserializer extends StdDeserializer<Embedded>
    {
        EmbeddedDeserializer()
        {
            super(Embedded.class);
        }

        @Override
        public Embedded deserialize(JsonParser p, DeserializationContext ctxt)
        {
            // Re-entrant use of the same mapper while the outer parser is in the middle of an object.
            return new Embedded(mapper.readValue(p.getBinaryValue(), Payload.class));
        }
    }

    static
    {
        mapper = MessagePackMapper.builder(new MessagePackFactory())
                .addModule(new SimpleModule()
                        .addSerializer(Embedded.class, new EmbeddedSerializer())
                        .addDeserializer(Embedded.class, new EmbeddedDeserializer()))
                .build();
    }

    private static Outer sample()
    {
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            numbers.add(i * 1000);
        }
        // A payload large enough that the inner write spans more than one buffer flush.
        return new Outer("before", new Embedded(new Payload("x".repeat(20_000), Long.MAX_VALUE)), "after", numbers);
    }

    @Test
    public void nestedWriteLeavesOuterOutputIntact() throws IOException
    {
        byte[] bytes = mapper.writeValueAsBytes(sample());

        // Decode with msgpack-core to check the outer structure was not disturbed by the inner write.
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
            assertEquals(4, unpacker.unpackMapHeader());
            assertEquals("before", unpacker.unpackString());
            assertEquals("before", unpacker.unpackString());
            assertEquals("embedded", unpacker.unpackString());
            byte[] inner = unpacker.readPayload(unpacker.unpackBinaryHeader());
            assertEquals("after", unpacker.unpackString());
            assertEquals("after", unpacker.unpackString());
            assertEquals("numbers", unpacker.unpackString());
            assertEquals(100, unpacker.unpackArrayHeader());
            for (int i = 0; i < 100; i++) {
                assertEquals(i * 1000, unpacker.unpackInt());
            }
            assertFalse(unpacker.hasNext());

            assertArrayEquals(mapper.writeValueAsBytes(sample().embedded.payload), inner);
        }
    }

    @Test
    public void nestedReadLeavesOuterInputIntact() throws IOException
    {
        byte[] bytes = mapper.writeValueAsBytes(sample());

        Outer outer = mapper.readValue(bytes, Outer.class);
        assertEquals("before", outer.before);
        assertEquals(sample().embedded.payload, outer.embedded.payload);
        // Fields after the nested parse prove the outer parser resumed at the right position.
        assertEquals("after", outer.after);
        assertEquals(sample().numbers, outer.numbers);
    }

    @Test
    public void nestedReadFromStreamLeavesOuterInputIntact() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mapper.writeValue(out, sample());

        Outer outer = mapper.readValue(new ByteArrayInputStream(out.toByteArray()), Outer.class);
        assertEquals("before", outer.before);
        assertEquals(sample().embedded.payload, outer.embedded.payload);
        assertEquals("after", outer.after);
        assertEquals(sample().numbers, outer.numbers);
    }

    @Test
    public void nestingSeveralLevelsDeep() throws IOException
    {
        // Each level's payload is the previous level's whole document, so writes and reads nest three deep.
        byte[] level1 = mapper.writeValueAsBytes(sample());
        byte[] level2 = mapper.writeValueAsBytes(new Outer("l2", new Embedded(new Payload(new String(level1, StandardCharsets.ISO_8859_1), 2)), "l2", List.of(2)));
        byte[] level3 = mapper.writeValueAsBytes(new Outer("l3", new Embedded(new Payload(new String(level2, StandardCharsets.ISO_8859_1), 3)), "l3", List.of(3)));

        Outer o3 = mapper.readValue(level3, Outer.class);
        assertEquals("l3", o3.after);
        Outer o2 = mapper.readValue(o3.embedded.payload.text.getBytes(StandardCharsets.ISO_8859_1), Outer.class);
        assertEquals("l2", o2.after);
        Outer o1 = mapper.readValue(o2.embedded.payload.text.getBytes(StandardCharsets.ISO_8859_1), Outer.class);
        assertEquals("after", o1.after);
        assertEquals(sample().embedded.payload, o1.embedded.payload);
    }
}
