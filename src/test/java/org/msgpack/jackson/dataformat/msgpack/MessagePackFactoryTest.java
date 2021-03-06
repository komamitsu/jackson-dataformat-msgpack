package org.msgpack.jackson.dataformat.msgpack;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import org.junit.Test;
import org.msgpack.core.MessagePacker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class MessagePackFactoryTest extends MessagePackDataformatTestBase {
    @Test
    public void testCreateGenerator() throws IOException {
        JsonEncoding enc = JsonEncoding.UTF8;
        JsonGenerator generator = factory.createGenerator(out, enc);
        assertEquals(MessagePackGenerator.class, generator.getClass());
    }

    @Test
    public void testCreateParser() throws IOException {
        JsonParser parser = factory.createParser(in);
        assertEquals(MessagePackParser.class, parser.getClass());
    }
}
