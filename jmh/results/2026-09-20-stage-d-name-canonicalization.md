# Stage D: property-name canonicalization

Same machine, JVM and command as the earlier files. Compared against the Stage C final
column (step 3) in `2026-09-19-stage-c-direct-encoding.md`.

## Why

`./gradlew :jmh:jmh -PjmhProfilers=gc -PjmhIncludes=MsgpackReadBenchmark` before the change:

| Benchmark | gc.alloc.rate.norm |
|---|---|
| readPojoJson | 2096 B/op |
| readPojoMsgpack | 3056 B/op |

The 960-byte difference is exactly the fixture's 24 property names times roughly 40 bytes
per short `String` (object header plus `byte[]`). The parser decoded and allocated a new
`String` for every key of every object; Jackson's JSON parser returns interned names from
`ByteQuadsCanonicalizer`, so it allocates none. This also answers msgpack-java issue #497.

## Change

Property names go through the same `ByteQuadsCanonicalizer` that Jackson's own binary
parsers use: the factory owns a root table, each parser gets a child, and the raw UTF-8
bytes of a name are looked up as 4-byte quads before any decoding. A hit returns the
existing `String`; a miss decodes once and adds it. Values are never canonicalized.
`TokenStreamFactory.Feature.CANONICALIZE_PROPERTY_NAMES` turns it off.

## Result

| Benchmark | Stage C | Stage D | Change |
|---|---|---|---|
| readPojoJson | 687544 ± 7193 | 674602 ± 17956 | control |
| readPojoMsgpack | 643288 ± 8555 | **709022 ± 13419** | **+10%** |
| writePojoJson | 1065463 ± 14988 | 1064147 ± 37928 | control |
| writePojoMsgpack | 969425 ± 25577 | 976100 ± 10021 | unchanged |
| writeUTF8StringAscii | 113569 ± 2382 | 112367 ± 1353 | unchanged |
| writeUTF8StringNonAscii | 111097 ± 7840 | 110439 ± 5976 | unchanged |

| Benchmark | gc.alloc.rate.norm |
|---|---|
| readPojoJson | 2096 B/op |
| readPojoMsgpack | **2024 B/op** |

MessagePack read is now faster than Jackson's JSON read on the same POJO, and allocates
less per operation.
