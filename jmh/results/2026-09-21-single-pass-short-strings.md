# Single-pass encoding of short strings

Same machine, JVM and command as the earlier files. Compared against the review-fixes run in
`2026-09-20-review-fixes.md`.

## Change

`packString` used two passes over the chars: `utf8Length` to size the header, then
`encodeUtf8` to write the bytes. A string of at most 31 chars (every property name in the
fixture, and most values) is now encoded in one pass straight after a one-byte fixstr header.
If the UTF-8 form turns out to be 32 bytes or longer, the payload is shifted by one or two
bytes to make room for a str8 or str16 header instead. This is the approach Jackson's CBOR
generator takes. Longer strings keep the two-pass path.

## Result

| Benchmark | Review fixes | Single pass | Change |
|---|---|---|---|
| writePojoJson | 1065303 ± 46739 | 1089512 ± 14208 | control |
| writePojoMsgpack | 958969 ± 20518 | **1092242 ± 23037** | **+14%** |
| writeUTF8StringAscii | 114435 ± 1446 | 116490 ± 5486 | unchanged |
| writeUTF8StringNonAscii | 107553 ± 4637 | 108924 ± 1648 | unchanged |

Two runs agreed (1100054 and 1092242). MessagePack write is now level with Jackson's JSON
write on the same POJO. The long-string benchmarks are unaffected, as they never take the
short path.
