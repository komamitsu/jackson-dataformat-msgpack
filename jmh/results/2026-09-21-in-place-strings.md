# In-place encoding for every string that fits the buffer

Same machine, JVM and command as the earlier files. Follows
`2026-09-21-single-pass-short-strings.md`, which introduced the single pass for strings of at
most 31 chars.

## Change

The 31-char limit is gone. A char is at least one byte, so the char count already says
which header a string needs at minimum: 1 byte up to 31 chars, 2 up to 255, 3 up to 65535.
`packString` reserves that many bytes, encodes the string after them in one pass, and moves
the payload only if non-ASCII text pushed the byte length over the next boundary. ASCII text
never moves at any length. Only a string whose worst case (3 bytes per char plus 5) does not
fit the buffer, about 2,660 chars by default, still counts its bytes first.

## Result

| Benchmark | 31-char single pass | In-place for all | Change |
|---|---|---|---|
| writePojoJson | 1089512 ± 14208 | 1064693 ± 13349 | control |
| writePojoMsgpack | 1092242 ± 23037 | **1150836 ± 20209** | **+5%** |
| WriteStringBenchmark.shortAscii (23 chars) | 133620 ± 2029 | 136514 ± 3032 | unchanged |
| WriteStringBenchmark.shortNonAscii (10 CJK) | 95455 ± 1677 | 96204 ± 824 | unchanged |
| WriteStringBenchmark.shortNonAsciiShifted (20 CJK) | 49070 ± 708 | 50894 ± 1149 | unchanged |
| WriteStringBenchmark.longAscii (50 chars) | 68404 ± 2531 | **96500 ± 751** | **+41%** |
| WriteStringBenchmark.longNonAscii (44 mixed) | 37344 ± 642 | **60295 ± 881** | **+61%** |
| writeUTF8StringAscii | 116490 ± 5486 | 118396 ± 3108 | unchanged |
| writeUTF8StringNonAscii | 108924 ± 1648 | 113727 ± 7012 | unchanged |

For reference, the same strings with the single pass disabled entirely (two passes for all):
shortNonAscii 85994, shortNonAsciiShifted 40812. The in-place path wins even when the copy
runs.

MessagePack write on the POJO benchmark is now 8% ahead of Jackson's JSON writer and 66%
ahead of the msgpack-core based 3.0.0 baseline (694360).
