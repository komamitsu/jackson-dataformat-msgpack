# Review fixes: control run

Same machine, JVM and command as the earlier files. Compared against the Stage D column in
`2026-09-20-stage-d-name-canonicalization.md`.

## What changed

Six review findings, none on a benchmarked hot path: the generator validates the nesting
limit before it touches the write context, `close()` writes nil for a property name whose
value never came, the parser reports the reserved `0xc1` byte instead of throwing
`NullPointerException`, and `packTimestamp` builds its payload through the routine
`timestampPayload` already used. Plus README and build-script cleanups.

## Result

| Benchmark | Stage D | Review fixes | Change |
|---|---|---|---|
| readPojoJson | 674602 ± 17956 | 670560 ± 13623 | control |
| readPojoMsgpack | 709022 ± 13419 | 689847 ± 8167 | unchanged within error |
| writePojoJson | 1064147 ± 37928 | 1065303 ± 46739 | control |
| writePojoMsgpack | 976100 ± 10021 | 958969 ± 20518 | unchanged within error |
| writeUTF8StringAscii | 112367 ± 1353 | 114435 ± 1446 | unchanged |
| writeUTF8StringNonAscii | 110439 ± 5976 | 107553 ± 4637 | unchanged |

The two MessagePack numbers moved by 2 to 3% with overlapping error bars, the same spread
the controls show between runs. The nesting check moved from after to before the context
is created, so the per-container cost is the same; the nil-on-close branch only runs when a
generator is closed mid-object; the `0xc1` comparison is one enum check per value.
