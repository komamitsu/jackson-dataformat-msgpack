# Baseline: 3.0.0 on msgpack-core

Reference numbers before the wire format is reimplemented on Jackson's buffers.
Compare future runs against this file.

- Commit: `7c4719d`
- Date: 2026-09-19
- JVM: JDK 17.0.2, OpenJDK 64-Bit Server VM, 17.0.2+8-86
- CPU: AMD Ryzen 9 5900HS
- Command: `./gradlew :jmh:jmh`
- Settings: 2 forks, 5 warmup + 5 measurement iterations of 1 s each (from the benchmark annotations)

| Benchmark | Mode | Cnt | Score | Error | Units |
|---|---|---|---|---|---|
| MsgpackReadBenchmark.readPojoJson | thrpt | 10 | 671589.923 | ± 20544.312 | ops/s |
| MsgpackReadBenchmark.readPojoMsgpack | thrpt | 10 | 588365.061 | ± 6489.866 | ops/s |
| MsgpackWriteBenchmark.writePojoJson | thrpt | 10 | 1074511.310 | ± 21590.608 | ops/s |
| MsgpackWriteBenchmark.writePojoMsgpack | thrpt | 10 | 694359.721 | ± 23815.474 | ops/s |
| WriteUTF8StringBenchmark.writeUTF8StringAscii | thrpt | 10 | 95600.367 | ± 675.393 | ops/s |
| WriteUTF8StringBenchmark.writeUTF8StringNonAscii | thrpt | 10 | 93577.160 | ± 1595.829 | ops/s |

MessagePack relative to JSON on the same POJO: read is 12% slower, write is 35% slower.

Two costs contribute to the write gap. MessagePack container headers carry an element
count, so the generator buffers children until the container closes. Separately, bytes
pass through msgpack-core's `MessageBuffer` before reaching Jackson's output. Replacing
msgpack-core removes only the second; measure it in isolation before expecting the gap
to close.
