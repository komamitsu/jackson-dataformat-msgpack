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
import org.msgpack.core.MessagePacker;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Property names are resolved through the factory's symbol table, so a key seen before
 * comes back as the same String instance. Values are never canonicalized.
 */
public class PropertyNameCanonicalizationTest
{
    // One name per quad-length branch in MessagePackReader.readName, plus edge cases
    // that must not collide: NULs, non-ASCII, and names differing only in the last byte.
    private static final String[] NAMES = {
            "a", "ab", "abc", "abcd",
            "abcde", "abcdefgh",
            "abcdefghi", "abcdefghijkl",
            "abcdefghijklm", "abcdefghijklmnop", "abcdefghijklmnopq",
            "a\u0000", "a\u0000\u0000", "abcd\u0000", "abcd\u0000\u0000\u0000\u0000",
            "é", "日本語", "key😀",
            "abcc", "abce",
            "a much longer property name that needs the quad buffer to grow past its initial size",
    };

    private static byte[] objects(int count) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MessagePacker packer = MessagePack.newDefaultPacker(out)) {
            packer.packArrayHeader(count);
            for (int i = 0; i < count; i++) {
                packer.packMapHeader(NAMES.length);
                for (String name : NAMES) {
                    packer.packString(name);
                    packer.packInt(i);
                }
            }
        }
        return out.toByteArray();
    }

    private static List<List<String>> readNames(JsonParser p)
    {
        List<List<String>> all = new ArrayList<>();
        assertEquals(JsonToken.START_ARRAY, p.nextToken());
        while (p.nextToken() == JsonToken.START_OBJECT) {
            List<String> names = new ArrayList<>();
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                names.add(p.currentName());
                p.nextToken();
            }
            all.add(names);
        }
        return all;
    }

    // Short names are padded with 0xff bytes before lookup. Raw 0xff never occurs in valid
    // UTF-8, but this reader tolerates malformed input, so a name that really contains
    // 0xff bytes must not resolve to the padded name it happens to match.
    @Test
    public void malformedNameDoesNotCollideWithPaddedName() throws IOException
    {
        byte[][] pairs = {
                {'a'}, {(byte) 0xff, (byte) 0xff, (byte) 0xff, 'a'},
                {'a', 'b'}, {(byte) 0xff, (byte) 0xff, 'a', 'b'},
                {'a', 'b', 'c', 'd', 'e'}, {'a', 'b', 'c', 'd', (byte) 0xff, (byte) 0xff, (byte) 0xff, 'e'},
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MessagePacker packer = MessagePack.newDefaultPacker(out)) {
            packer.packArrayHeader(1);
            packer.packMapHeader(pairs.length);
            for (byte[] raw : pairs) {
                packer.packRawStringHeader(raw.length);
                packer.writePayload(raw);
                packer.packInt(1);
            }
        }
        MessagePackFactory factory = new MessagePackFactory();
        for (JsonParser p : new JsonParser[] {
                factory.createParser(ObjectReadContext.empty(), out.toByteArray()),
                factory.createParser(ObjectReadContext.empty(), new ByteArrayInputStream(out.toByteArray())),
        }) {
            try (p) {
                List<String> names = readNames(p).get(0);
                for (int i = 0; i < pairs.length; i++) {
                    assertEquals(new String(pairs[i], java.nio.charset.StandardCharsets.UTF_8), names.get(i));
                }
            }
        }
    }

    // A bin-typed key is exposed as a property name too, and goes through the same table.
    @Test
    public void binaryKeysAreCanonicalizedLikeStringKeys() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MessagePacker packer = MessagePack.newDefaultPacker(out)) {
            packer.packArrayHeader(2);
            for (int i = 0; i < 2; i++) {
                packer.packMapHeader(1);
                packer.packBinaryHeader(3);
                packer.writePayload(new byte[] {'k', 'e', 'y'});
                packer.packInt(i);
            }
        }
        try (JsonParser p = new MessagePackFactory().createParser(ObjectReadContext.empty(), out.toByteArray())) {
            List<List<String>> all = readNames(p);
            assertEquals("key", all.get(0).get(0));
            assertSame(all.get(0).get(0), all.get(1).get(0));
        }
    }

    @Test
    public void repeatedNamesAreTheSameInstance() throws IOException
    {
        byte[] data = objects(3);
        MessagePackFactory factory = new MessagePackFactory();
        for (JsonParser p : new JsonParser[] {
                factory.createParser(ObjectReadContext.empty(), data),
                factory.createParser(ObjectReadContext.empty(), new ByteArrayInputStream(data)),
        }) {
            try (p) {
                List<List<String>> all = readNames(p);
                assertEquals(3, all.size());
                for (int i = 0; i < NAMES.length; i++) {
                    assertEquals(NAMES[i], all.get(0).get(i));
                    assertSame(all.get(0).get(i), all.get(1).get(i), NAMES[i]);
                    assertSame(all.get(0).get(i), all.get(2).get(i), NAMES[i]);
                }
            }
        }
    }

    @Test
    public void namesAreSharedAcrossParsersOfTheSameFactory() throws IOException
    {
        byte[] data = objects(1);
        MessagePackFactory factory = new MessagePackFactory();
        List<String> first;
        try (JsonParser p = factory.createParser(ObjectReadContext.empty(), data)) {
            first = readNames(p).get(0);
        }
        try (JsonParser p = factory.createParser(ObjectReadContext.empty(), data)) {
            List<String> second = readNames(p).get(0);
            for (int i = 0; i < NAMES.length; i++) {
                assertSame(first.get(i), second.get(i), NAMES[i]);
            }
        }
        try (JsonParser p = new MessagePackFactory().createParser(ObjectReadContext.empty(), data)) {
            List<String> other = readNames(p).get(0);
            assertEquals(first, other);
            assertNotSame(first.get(0), other.get(0), "a different factory has its own table");
        }
    }

    @Test
    public void valuesAreNotCanonicalized() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MessagePacker packer = MessagePack.newDefaultPacker(out)) {
            packer.packArrayHeader(2).packString("same").packString("same");
        }
        try (JsonParser p = new MessagePackFactory().createParser(ObjectReadContext.empty(), out.toByteArray())) {
            p.nextToken();
            p.nextToken();
            String a = p.getString();
            p.nextToken();
            String b = p.getString();
            assertEquals(a, b);
            assertNotSame(a, b);
        }
    }

    @Test
    public void canonicalizationCanBeDisabled() throws IOException
    {
        byte[] data = objects(2);
        MessagePackFactory factory = (MessagePackFactory) new MessagePackFactory().rebuild()
                .disable(TokenStreamFactory.Feature.CANONICALIZE_PROPERTY_NAMES)
                .build();
        try (JsonParser p = factory.createParser(ObjectReadContext.empty(), data)) {
            List<List<String>> all = readNames(p);
            assertEquals(NAMES[0], all.get(0).get(0));
            assertNotSame(all.get(0).get(0), all.get(1).get(0));
        }
    }

    @Test
    public void databindRoundTripStillCorrect() throws IOException
    {
        ObjectMapper mapper = new MessagePackMapper();
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String name : NAMES) {
            map.put(name, name.length());
        }
        byte[] bytes = mapper.writeValueAsBytes(List.of(map, map, map));
        List<?> back = mapper.readValue(bytes, List.class);
        assertEquals(3, back.size());
        for (Object o : back) {
            assertEquals(map, o);
        }
    }
}
