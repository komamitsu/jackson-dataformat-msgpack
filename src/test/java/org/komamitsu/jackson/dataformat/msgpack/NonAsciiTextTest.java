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

import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Text outside ASCII must survive a round trip regardless of the platform's default
 * charset. The test JVM runs with a non-UTF-8 default (see build.gradle.kts), which is
 * what exposed msgpack-java issue #908 on Windows.
 */
public class NonAsciiTextTest
{
    private static final String[] SAMPLES = {
            "çÇğĞıİöÖşŞüÜ",   // Turkish, from the issue
            "Тест кириллицы", // Cyrillic, from the issue
            "日本語のテキスト",                            // Japanese
            "emoji 😀🎉",                                            // supplementary plane
            "mixed ASCII é 三 😀 end",
    };

    @Test
    public void defaultCharsetIsNotUtf8InThisJvm()
    {
        // If this fails the guard below is not exercising anything.
        assertNotEquals(StandardCharsets.UTF_8, Charset.defaultCharset());
    }

    @Test
    public void roundTripsAsValuesKeysAndNested() throws IOException
    {
        ObjectMapper mapper = new MessagePackMapper();
        for (String s : SAMPLES) {
            assertEquals(s, mapper.readValue(mapper.writeValueAsBytes(s), String.class));
            assertEquals(List.of(s), mapper.readValue(mapper.writeValueAsBytes(List.of(s)), List.class));
            assertEquals(Map.of(s, s), mapper.readValue(mapper.writeValueAsBytes(Map.of(s, s)), Map.class));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            mapper.writeValue(out, s);
            assertEquals(s, mapper.readValue(new ByteArrayInputStream(out.toByteArray()), String.class));
        }
    }

    @Test
    public void bytesOnTheWireAreUtf8() throws IOException
    {
        ObjectMapper mapper = new MessagePackMapper();
        for (String s : SAMPLES) {
            byte[] bytes = mapper.writeValueAsBytes(s);
            try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
                assertEquals(s, unpacker.unpackString());
            }
            byte[] expectedPayload = s.getBytes(StandardCharsets.UTF_8);
            int headerLength = bytes.length - expectedPayload.length;
            byte[] payload = new byte[expectedPayload.length];
            System.arraycopy(bytes, headerLength, payload, 0, payload.length);
            assertEquals(new String(expectedPayload, StandardCharsets.UTF_8), new String(payload, StandardCharsets.UTF_8));
        }
    }
}
