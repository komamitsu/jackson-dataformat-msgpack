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
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inputs crafted to exhaust memory or the stack. Each must fail with a bounded, typed
 * exception and without allocating in proportion to what the input claims.
 *
 * <p>Corresponds to msgpack-java issues #1015 (CVE-2026-90472, unbounded recursion),
 * #1014 (CVE-2026-90473, MAP32 overflow) and #657 (huge declared sizes).
 */
public class HostileInputTest
{
    private static final byte STR32 = (byte) 0xdb;
    private static final byte BIN32 = (byte) 0xc6;
    private static final byte EXT32 = (byte) 0xc9;
    private static final byte ARRAY32 = (byte) 0xdd;
    private static final byte MAP32 = (byte) 0xdf;
    private static final byte FIXARRAY1 = (byte) 0x91;
    private static final byte FIXMAP1 = (byte) 0x81;

    private static final byte[] MAX_LENGTH = {(byte) 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff};

    private static byte[] header(byte format)
    {
        byte[] b = new byte[5];
        b[0] = format;
        System.arraycopy(MAX_LENGTH, 0, b, 1, 4);
        return b;
    }

    private static JsonParser parser(byte[] data)
    {
        return new MessagePackFactory().createParser(ObjectReadContext.empty(), data);
    }

    private static JsonParser streamParser(byte[] data)
    {
        return new MessagePackFactory().createParser(ObjectReadContext.empty(), new ByteArrayInputStream(data));
    }

    // Five bytes claim a 2 GB string. Allocating for it first would exhaust the heap; the
    // declared length must be rejected against StreamReadConstraints before that.
    @Test
    public void hugeDeclaredStringIsRejectedBeforeAllocation()
    {
        for (byte[] input : new byte[][] {header(STR32), header(BIN32)}) {
            try (JsonParser p = parser(input)) {
                assertThrows(StreamConstraintsException.class, p::nextToken);
            }
            try (JsonParser p = streamParser(input)) {
                assertThrows(StreamConstraintsException.class, p::nextToken);
            }
        }
        byte[] ext = new byte[6];
        ext[0] = EXT32;
        System.arraycopy(MAX_LENGTH, 0, ext, 1, 4);
        ext[5] = 7;
        try (JsonParser p = parser(ext)) {
            assertThrows(StreamConstraintsException.class, p::nextToken);
        }
    }

    // Within the constraint but beyond what the array holds: fail without allocating the
    // declared size.
    @Test
    public void stringLongerThanTheArrayFailsWithoutAllocating()
    {
        byte[] input = {STR32, 0x00, 0x10, 0x00, 0x00, 'a', 'b'};
        try (JsonParser p = parser(input)) {
            assertThrows(UnexpectedEndOfInputException.class, p::nextToken);
        }
    }

    // A raised limit is honoured too, so the check is the constraint and not a hard cap.
    @Test
    public void stringLimitIsTheConfiguredConstraint()
    {
        MessagePackFactory factory = (MessagePackFactory) new MessagePackFactory().rebuild()
                .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(4).build())
                .build();
        byte[] fits = {(byte) 0xa4, 'a', 'b', 'c', 'd'};
        byte[] tooLong = {(byte) 0xa5, 'a', 'b', 'c', 'd', 'e'};
        try (JsonParser p = factory.createParser(ObjectReadContext.empty(), fits)) {
            assertEquals(JsonToken.VALUE_STRING, p.nextToken());
            assertEquals("abcd", p.getString());
        }
        try (JsonParser p = factory.createParser(ObjectReadContext.empty(), tooLong)) {
            assertThrows(StreamConstraintsException.class, p::nextToken);
        }
    }

    // A container header claiming 2^31-1 entries allocates nothing by itself; the parser
    // simply runs out of input.
    @Test
    public void hugeDeclaredContainerAllocatesNothing()
    {
        for (byte format : new byte[] {ARRAY32, MAP32}) {
            try (JsonParser p = parser(header(format))) {
                JsonToken start = p.nextToken();
                assertTrue(start == JsonToken.START_ARRAY || start == JsonToken.START_OBJECT);
                assertThrows(UnexpectedEndOfInputException.class, p::nextToken);
            }
        }
        ObjectMapper mapper = new MessagePackMapper();
        assertThrows(UnexpectedEndOfInputException.class, () -> mapper.readValue(header(ARRAY32), JsonNode.class));
    }

    // MAP32 with a count whose doubled key/value total overflows int must not desynchronise
    // the parser. skipChildren() walks tokens rather than counting, so it fails cleanly.
    @Test
    public void mapCountThatOverflowsWhenDoubledIsHandled()
    {
        byte[] input = new byte[5 + 6];
        input[0] = MAP32;
        input[1] = (byte) 0x7f;
        input[2] = (byte) 0xff;
        input[3] = (byte) 0xff;
        input[4] = (byte) 0xff;
        input[5] = (byte) 0xa1;
        input[6] = 'k';
        input[7] = (byte) 0xa1;
        input[8] = 'v';
        input[9] = (byte) 0xa1;
        input[10] = 'x';
        try (JsonParser p = parser(input)) {
            assertEquals(JsonToken.START_OBJECT, p.nextToken());
            assertThrows(UnexpectedEndOfInputException.class, p::skipChildren);
        }
    }

    // 100,000 nested single-element arrays in 100 KB. Recursion would overflow the stack;
    // the nesting-depth constraint must stop it first, with a typed exception.
    @Test
    public void deepNestingStopsAtTheConstraintNotTheStack()
    {
        byte[] input = new byte[100_000];
        Arrays.fill(input, FIXARRAY1);
        input[input.length - 1] = (byte) 0xc0;

        try (JsonParser p = parser(input)) {
            StreamConstraintsException e = assertThrows(StreamConstraintsException.class, () -> {
                while (p.nextToken() != null) {
                    // consume
                }
            });
            assertTrue(e.getMessage().contains("nesting depth"), e.getMessage());
        }

        // Through databind as well, whose untyped deserializer recurses per level.
        ObjectMapper mapper = new MessagePackMapper();
        assertThrows(StreamConstraintsException.class, () -> mapper.readValue(input, Object.class));
        assertThrows(StreamConstraintsException.class, () -> mapper.readValue(input, JsonNode.class));

        byte[] maps = new byte[200_000];
        for (int i = 0; i < maps.length - 1; i += 2) {
            maps[i] = FIXMAP1;
            maps[i + 1] = (byte) 0xa0;
        }
        maps[maps.length - 1] = (byte) 0xc0;
        assertThrows(StreamConstraintsException.class, () -> mapper.readValue(maps, Object.class));
    }

    @Test
    public void nestingUpToTheConstraintStillParses() throws IOException
    {
        int depth = StreamReadConstraints.defaults().getMaxNestingDepth();
        byte[] input = new byte[depth + 1];
        Arrays.fill(input, 0, depth, FIXARRAY1);
        input[depth] = 1;
        Object value = new MessagePackMapper().readValue(input, Object.class);
        for (int i = 0; i < depth; i++) {
            value = ((java.util.List<?>) value).get(0);
        }
        assertEquals(1, value);
    }

    // 0xc1 is the one byte the MessagePack spec reserves and never assigns. It has no value
    // type, so it must be reported like any other malformed input, not crash the parser.
    @Test
    public void reservedFormatByteIsReportedNotThrownAsNpe()
    {
        byte[] bare = {(byte) 0xc1};
        byte[] nested = {FIXARRAY1, (byte) 0xc1};
        try (JsonParser p = parser(bare)) {
            assertThrows(StreamReadException.class, p::nextToken);
        }
        try (JsonParser p = streamParser(nested)) {
            assertEquals(JsonToken.START_ARRAY, p.nextToken());
            assertThrows(StreamReadException.class, p::nextToken);
        }
        ObjectMapper mapper = new MessagePackMapper();
        assertThrows(StreamReadException.class, () -> mapper.readValue(bare, Object.class));
        assertThrows(StreamReadException.class, () -> mapper.readValue(nested, JsonNode.class));
    }
}
