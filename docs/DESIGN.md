# Internal design

How the library encodes and decodes MessagePack on top of Jackson 3's streaming API. This is
for people changing the code; the README covers usage.

## 1. Class map

```mermaid
flowchart LR
    subgraph Jackson
        OM[ObjectMapper / databind]
        TSF[TokenStreamFactory]
        IOC[IOContext + BufferRecycler]
        SYM[ByteQuadsCanonicalizer]
    end
    subgraph This library
        F[MessagePackFactory]
        G[MessagePackGenerator]
        W[MessagePackWriter]
        P[MessagePackParser]
        R[MessagePackReader]
        WC[MessagePackWriteContext]
        RC[MessagePackReadContext]
        MF[MessageFormat / Code]
    end
    OM --> F
    TSF --- F
    F -->|createGenerator| G
    F -->|createParser| P
    G --> W
    G --> WC
    P --> R
    P --> RC
    W --> MF
    R --> MF
    IOC -.borrows buffers.-> W
    IOC -.borrows buffers.-> R
    SYM -.property names.-> R
```

| Class | Role |
|---|---|
| `MessagePackFactory` / `MessagePackFactoryBuilder` | Jackson `TokenStreamFactory`. Owns the root symbol table and the format options (str8, integer keys, custom extension deserializers). |
| `MessagePackGenerator` | Jackson `JsonGenerator`. Maps write calls to writer calls and tracks the write context. |
| `MessagePackWriter` | Encodes values into a byte buffer and writes it to the `OutputStream`. Knows nothing about Jackson tokens. |
| `MessagePackWriteContext` | Jackson `TokenStreamContext` for writing: element counts, property-name state, header position of each open container. |
| `MessagePackParser` | Jackson `JsonParser`. Turns decoded values into tokens and holds the current value. |
| `MessagePackReader` | Decodes values from a byte array or an `InputStream` through a buffer. |
| `MessagePackReadContext` | Jackson `TokenStreamContext` for reading: expected element counts, current name. |
| `MessageFormat` / `Code` | The format-byte table of the MessagePack spec. |
| `MessagePackKeySerializer`, `MessagePackSerializedString` | Non-String map keys on the write side. |
| `TimestampExtensionModule`, `MessagePackExtensionType`, `ExtensionTypeCustomDeserializers` | Extension type (-1 timestamp, and user-defined types). |

msgpack-core is not used at runtime. It is a test dependency, used as the reference
implementation that every encoded and decoded value is compared against.

## 2. Write path

### 2.1 The problem: counted containers

A MessagePack array or map header carries the element count and precedes the elements.
Jackson's streaming API delivers elements one by one and only signals the end with
`writeEndArray`/`writeEndObject`. So the header cannot be final until the container closes,
and the bytes between the header and the close must still be in memory to patch it.

CBOR and Smile do not have this problem (CBOR has indefinite-length containers with a break
marker, Smile has start/end markers), which is why Jackson's own binary generators stream
every byte straight out and this one cannot.

### 2.2 Hold mode and header patching

`MessagePackWriter` keeps one output buffer. While no container is open, the buffer is
flushed to the stream whenever it fills. While a container is open (`holdDepth > 0`), the
buffer is never flushed; it grows instead, so the header stays reachable.

```mermaid
sequenceDiagram
    participant Gen as MessagePackGenerator
    participant Ctx as MessagePackWriteContext
    participant W as MessagePackWriter
    Gen->>W: openContainer(map, sizeHint)
    Note over W: holdDepth++<br/>reserve header bytes at offset h
    W-->>Gen: h
    Gen->>Ctx: setHeader(h, reservedLength)
    loop each element
        Gen->>W: packX(value)
        Note over W: buffer grows if full, never flushes
        Gen->>Ctx: writeValue() (count++)
    end
    Gen->>W: closeContainer(map, h, reservedLength, count)
    Note over W: finalHeaderLength = headerLength(count)<br/>if finalHeaderLength != reservedLength: shift elements by the difference<br/>write final header at h<br/>holdDepth--
    Gen->>Ctx: pop to parent
    Note over W: holdDepth == 0 again: next ensure() may flush
```

With no size hint, one byte is reserved (fixarray/fixmap, up to 15 elements). If the final
count needs a 3- or 5-byte header, the elements are shifted right once at close. With a size
hint (`writeStartArray(Object, int)`), the header for that size is written immediately and
close only patches it if the hint was wrong. Hints come from databind for collections of
known size and are used but not trusted: databind passes -1 when views or filters are
involved, and callers can be wrong.

### 2.2.1 Where the header is

`openContainer` returns the buffer offset at which it reserved the header, and the generator
stores it in the container's `MessagePackWriteContext` together with the number of bytes
reserved (`setHeader(offset, reservedLength)`). The count itself is not stored anywhere in
the buffer until close; the context counts elements as they are written
(`getEntryCount()`), and `closeContainer` gets all three from the context.

The offset is an index into the writer's buffer, and it stays valid for the whole time the
container is open because:

- the buffer is never flushed while `holdDepth > 0`, so bytes never move to the stream and
  offsets are never reset to 0;
- `grow()` copies the buffer from index 0 into the larger array, so every offset is the same
  in the new buffer;
- closing an inner container only shifts bytes *after* its own header, and every enclosing
  container's header lies before it, so outer offsets are unaffected;
- a nested generator for a complex map key shares the same writer and the same buffer, so
  its offsets are in the same index space.

Nested containers therefore just nest: each level's context holds its own offset, and
`holdDepth` counts open levels. Everything from the outermost open container's header
onward is in the buffer until that container closes; then `holdDepth` drops to 0 and the
next `ensure()` may flush.

### 2.3 Buffer ownership

Transitions are labelled "event, guard / action".

```mermaid
stateDiagram-v2
    [*] --> Borrowed: writer created / allocWriteEncodingBuffer()
    Borrowed --> Borrowed: buffer full, holdDepth == 0 / flushBuffer()
    Borrowed --> Owned: buffer full, holdDepth > 0 / grow(), releaseWriteEncodingBuffer(old)
    Owned --> Owned: buffer full / grow()
    Borrowed --> [*]: release() / releaseWriteEncodingBuffer(buf)
    Owned --> [*]: release() / drop buf
```

`grow()` allocates an array of twice the size (or as large as needed), copies the used part,
and makes it the current buffer.

The initial buffer comes from Jackson's `BufferRecycler` (8000 bytes by default) so that a
generator per `writeValueAsBytes` does not allocate. `grow()` is only reached inside an open
container, when flushing is not allowed. The borrowed buffer is handed back the moment it is
replaced, and a buffer the writer allocated itself is never given to the pool (the pool
would then hand oversized arrays to other users). Small values never reach `grow()`.

Retained memory per idle thread is dominated by Jackson's own `ByteArrayBuilder` pooling,
not by this writer; see `jmh/results/2026-09-19-stage-b-own-wire-format.md`.

### 2.4 Strings

`packString` needs the UTF-8 byte length before it can write the header, because the header
comes first and its size depends on that length (fixstr for up to 31 bytes, str8 up to 255,
str16 up to 65535, str32 above). Two private helpers do the work, both walking the chars of
the `String` directly with `charAt` and allocating nothing:

- `utf8Length(s)` returns how many bytes `s` takes in UTF-8. It writes nothing.
- `encodeUtf8(s, buf, off)` writes the UTF-8 bytes of `s` into `buf` starting at `off` and
  returns the index after the last byte written.

Which path a string takes depends on its char count:

```mermaid
flowchart TD
    S[packString s] --> L{s.length <= 31 chars?}
    L -->|yes| SP[write the UTF-8 bytes at pos + 1,<br/>leaving 1 byte for the header<br/>encodeUtf8]
    SP --> B{byte length < 32?}
    B -->|yes| F1[fixstr: write the 1-byte header<br/>into the byte left free]
    B -->|no, str8 enabled| F2[str8: move the bytes right by 1,<br/>write the 2-byte header]
    B -->|no, str8 disabled| F3[str16: move the bytes right by 2,<br/>write the 3-byte header]
    L -->|no| C[count the UTF-8 bytes<br/>utf8Length]
    C --> H[write the header for that length<br/>packRawStringHeader]
    H --> R{do the bytes fit<br/>after the header?}
    R -->|yes, or a container is open so the buffer grows| E[write the UTF-8 bytes after the header<br/>encodeUtf8]
    R -->|no, and no container is open| FL[flush the buffer to the stream,<br/>then write the bytes at 0<br/>encodeUtf8]
    R -->|larger than the whole buffer| G[String.getBytes, then writePayload]
```

**Short path, up to 31 chars.** 31 chars encode to at most 93 bytes, so the header is 1, 2
or 3 bytes and `ensure(3 + 3 * charLen)` makes room for the worst case. The bytes of the
string are written before the header is known, starting one byte after `pos`. That one byte
is skipped on purpose: it is where the header will go if the string turns out to be a fixstr,
the common case. With `"abc"` as the example (`pos` is where the value starts in the buffer):

```text
step 1: encodeUtf8(s, buf, pos + 1)         buf[pos] is skipped, not yet written

        index:   pos    pos+1  pos+2  pos+3
                +------+------+------+------+
                |  ??  |  a   |  b   |  c   |     end = pos + 4, byteLen = 3
                +------+------+------+------+

step 2a: byteLen < 32, so the skipped byte becomes the fixstr header (0xa0 | byteLen)

                +------+------+------+------+
                | 0xa3 |  a   |  b   |  c   |     pos = end
                +------+------+------+------+
```

If `byteLen` is 32 or more the header needs 2 bytes (str8) or, with str8 disabled, 3 bytes
(str16). The string bytes are then moved right by 1 or 2 positions with an overlapping
in-place `System.arraycopy(buf, start, buf, start + shift, byteLen)`, and the header is
written in front. Here for a 40-byte string:

```text
step 2b: str8, arraycopy the 40 bytes from pos+1 to pos+2, then write the header

        index:   pos    pos+1  pos+2       pos+41
                +------+------+------+-----+------+
                | 0xd9 |  40  |  b0  | ... |  b39 |     pos = end + 1
                +------+------+------+-----+------+

step 2c: str16 (str8 disabled), arraycopy the 40 bytes from pos+1 to pos+3, then write the header

        index:   pos    pos+1  pos+2  pos+3       pos+42
                +------+------+------+------+-----+------+
                | 0xda | 0x00 |  40  |  b0  | ... |  b39 |     pos = end + 2
                +------+------+------+------+-----+------+
```

This is the path every property name and most values take. The `arraycopy` happens only
when a string of at most 31 chars encodes to 32 bytes or more, which needs non-ASCII text of
11 chars or more, and it moves under 100 bytes. Measured with `WriteStringBenchmark`, the
single pass is still ahead of the two-pass path even when the copy runs: 20 CJK chars (60
bytes, str8 shift) write at 49k ops/s against 41k for two passes, and 10 CJK chars (no shift)
at 95k against 86k. Counting the bytes first would cost a full extra pass over every string
to save an occasional small memmove.
`jmh/results/2026-09-21-single-pass-short-strings.md` shows the effect.

**Long path, 32 chars or more.** `utf8Length` walks the chars once and counts bytes without
allocating, the header is written, then `encodeUtf8` walks the chars again and writes the
bytes straight into the buffer. Two passes but no intermediate array. A string larger than
the whole buffer is the one case that goes through `String.getBytes`.

Neither path allocates per character: `charAt` returns a primitive `char`, and the
`WriteStringBenchmark` gc profile shows 280 bytes per operation for 500 strings, which is the
generator itself.

`utf8Length` and `encodeUtf8` must agree with each other and with `String.getBytes(UTF_8)`,
or the header would not match the bytes: an unpaired surrogate counts as one byte and is
written as `?`.

### 2.5 Close semantics

`close()` with containers still open follows Jackson's `AUTO_CLOSE_CONTENT` (on by default):

- On: open containers are closed in order and the result is flushed. A property name with no
  value gets nil, so the output stays valid MessagePack.
- Off: the whole unfinished root value is discarded (`discardFrom(outermostHeaderOffset)`)
  and complete root values before it are flushed. Emitting the enclosing containers with
  partial counts would produce a valid-looking value that differs from what was written,
  which is worse than emitting nothing.

Exceptions thrown mid-value leave the generator consistent, because every check (nesting
depth, value expected) runs before any state is changed.

### 2.6 Complex map keys

A non-scalar map key (`MessagePackKeySerializer` on a POJO) is serialized by a nested
`MessagePackGenerator` that shares the parent's `MessagePackWriter`, so the key's containers
are patched in place inside the parent's buffer. The nested generator has its own write
context stack, seeded with the parent's nesting depth so `maxNestingDepth` still holds.

## 3. Read path

### 3.1 Sources and buffering

```mermaid
flowchart TD
    A[byte array source] -->|no copy| R[MessagePackReader<br/>buf, pos, end]
    S[InputStream source] -->|allocReadIOBuffer| R
    R -->|ensure n| C{end - pos >= n?}
    C -->|yes| D[decode from buf]
    C -->|no, array| E[EOFException]
    C -->|no, stream| F[fillAtLeast n:<br/>move unread to front,<br/>read until n available]
    F -->|readAhead| G[read as much as fits]
    F -->|no readAhead| H[read exactly what is needed]
```

A byte-array source is decoded in place. A stream source borrows an 8000-byte buffer from
the `IOContext` and refills it on demand. `readAhead` is on unless `AUTO_CLOSE_SOURCE` is
disabled: then the caller may keep reading the stream after the parser is done, so the
reader must not consume bytes beyond the current value.

A payload larger than the buffer (a long string or binary) is read directly into its own
array with `readPayload`, never by growing the buffer.

### 3.2 Token state machine

`MessagePackParser._nextToken` decides what the next value means from the read context:

```mermaid
stateDiagram-v2
    [*] --> Root
    Root --> Value: any value
    Root --> InArray: array header (push context with count)
    Root --> InObject: map header (push context with count)
    InArray --> InArray: value / nested container
    InArray --> Parent: count reached → END_ARRAY, pop
    InObject --> ExpectValue: value in key position → PROPERTY_NAME
    ExpectValue --> InObject: value
    InObject --> Parent: count reached → END_OBJECT, pop
```

The read context knows the declared count of each container and reports END_ARRAY /
END_OBJECT when it is reached; there are no end markers in the stream. `isObjectValueSet`
(in an object and the last token was not PROPERTY_NAME) means the next value is a key.

Key handling by MessagePack type:

| Key type | Token | `currentName()` | Notes |
|---|---|---|---|
| str | PROPERTY_NAME | the string | canonicalized (3.3) |
| int / float / bool | PROPERTY_NAME | `String.valueOf` | `isCurrentFieldId()` tells a `KeyDeserializer` it was an integer |
| nil | PROPERTY_NAME | null | strict duplicate detection tracks a second nil key |
| bin | PROPERTY_NAME | UTF-8 decoding | raw bytes kept, `getBinaryValue()` returns them; not canonicalized |
| ext | PROPERTY_NAME | `toString()` of the deserialized value | |
| array / map | error | | no property name can represent it |

Values are decoded eagerly in `_nextToken` (numbers into the narrowest of int/long/BigInteger,
strings into a `String`), so the accessors only return fields. `nextToken()` after end of
input or after `close()` returns null and clears the current token.

### 3.3 Property-name canonicalization

Property names go through the same `ByteQuadsCanonicalizer` that Jackson's JSON, CBOR and
Smile parsers use. The factory owns a root table; each parser gets a child, which is merged
back into the root on close. A name's raw UTF-8 bytes are packed into 4-byte quads and looked
up before any decoding; a hit returns the existing `String`, a miss decodes once and adds it.
Values are never canonicalized. `TokenStreamFactory.Feature.CANONICALIZE_PROPERTY_NAMES`
turns it off.

Two edge cases:

- A short name's last quad is padded with 0xff bytes. 0xff never occurs in valid UTF-8, but
  the reader accepts malformed input, so a name that really contains 0xff could collide with
  a padded one. Such names are detected from the quads already computed and decoded without
  the table.
- A name longer than the whole read buffer is decoded like a string and kept out of the
  shared table, where it would only take up space.

Effect: `readPojoMsgpack` allocates 2024 bytes per operation against 2096 for Jackson's JSON
read of the same POJO (`jmh/results/2026-09-20-stage-d-name-canonicalization.md`).

## 4. Constraints and hostile input

`StreamReadConstraints` and `StreamWriteConstraints` from the factory are enforced:

| Constraint | Where |
|---|---|
| `maxStringLength` | string, binary and extension values: checked against the declared length before anything is allocated |
| `maxNameLength` | the same three in key position |
| `maxNestingDepth` (read) | on every array/map header |
| `maxNestingDepth` (write) | in `writeStartArray`/`writeStartObject`, before the context or the count is touched |
| `maxNumberLength` | not applicable, MessagePack numbers are fixed-size |

Rules the reader follows: a declared length is checked before allocating for it; a declared
container count is never used to pre-size anything (a 5-byte input can claim 2^31 elements);
the reserved format byte 0xc1 and any format in the wrong position (a container as a key)
are reported as `StreamReadException`, never as an unchecked exception. Timestamp nanosecond
fields above 999999999 are rejected instead of being folded into the seconds.
`HostileInputTest` pins these.

## 5. Format decisions

- **str8**: written for strings of 32 to 255 bytes unless `str8FormatSupport` is off, then
  str16. Readers accept both regardless.
- **Integer keys**: with `supportIntegerKeys`, `writePropertyId(long)` writes the key as an
  integer; otherwise as its decimal string. On the read side an integer key is always exposed
  as a string name with `isCurrentFieldId()` set.
- **BigInteger**: int64/uint64 when it fits, otherwise `IllegalArgumentException` (there is no
  bigger MessagePack integer). **BigDecimal**: written as an integer if it has no fraction,
  else as a double if that is exact, else `IllegalArgumentException`.
  `MessagePackMapper.Builder.handleBigIntegerAndBigDecimalAsString()` switches both to strings.
- **Timestamps**: extension type -1 in the 32-, 64- or 96-bit form, the smallest that fits.
  `TimestampExtensionModule` maps `Instant`.
- **Other extension types**: `MessagePackExtensionType` (type byte plus payload), with
  `ExtensionTypeCustomDeserializers` for turning a type into a Java object on read.
- **Null arguments**: `writeString(null)`, `writeNumber((BigInteger) null)` and the like
  write nil, as Jackson's own generators do.
- **Duplicate keys**: `STRICT_DUPLICATE_DETECTION` uses Jackson's `DupDetector` for string
  names and a flag for nil; distinct keys that print alike (`1` and `"1"`) collide, as in
  Jackson's CBOR generator.

## 6. Testing

- `MessagePackWriterTest` / `MessagePackReaderTest`: every value kind at every format
  boundary, compared byte for byte with msgpack-core's `MessagePacker` and decoded from its
  output, for both str8 settings and for array and stream sources.
- `MessagePackGeneratorTest` / `MessagePackParserTest`: the Jackson layer, including close
  semantics, contexts, complex keys and the accessor contract.
- `HostileInputTest`: inputs that claim huge sizes or depths must fail with a bounded, typed
  exception without allocating in proportion to the claim.
- `PropertyNameCanonicalizationTest`, `NestedUsageTest` (re-entrant `ObjectMapper` use on one
  thread), `NonAsciiTextTest` (the test JVM runs with a non-UTF-8 default charset so any
  charset-less conversion shows up).
- `MemoryLeakSoakTest` (`./gradlew soakTest -Psoak.seconds=120`, not part of `build`): four
  threads share one factory for the given time, every iteration using property names never
  seen before and values large enough to make the writer replace its pooled buffer, plus the
  abnormal close paths. The post-GC heap at the end must not exceed the post-GC heap after
  warm-up by more than 8 MB. Two minutes is about 2 million iterations and 50 million
  distinct names; the heap stays flat.
- Benchmarks: `./gradlew :jmh:jmh`, with `-PjmhIncludes=<regex>` and `-PjmhProfilers=gc`.
  Results and the reasoning behind each performance change are in `jmh/results/`.
