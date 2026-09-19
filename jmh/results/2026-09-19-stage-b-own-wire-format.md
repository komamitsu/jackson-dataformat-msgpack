# Stage B: 3.0.0 on the in-house wire format

Same machine, JVM and command as the baseline in `2026-09-19-baseline-msgpack-core.md`.

- Commit: `a365acb`
- Date: 2026-09-19
- JVM: JDK 17.0.2, OpenJDK 64-Bit Server VM, 17.0.2+8-86
- CPU: AMD Ryzen 9 5900HS
- Command: `./gradlew :jmh:jmh`

| Benchmark | Baseline ops/s | Stage B ops/s | Change |
|---|---|---|---|
| MsgpackReadBenchmark.readPojoJson | 671589.923 ± 20544.312 | 674309.389 ± 7942.914 | control |
| MsgpackReadBenchmark.readPojoMsgpack | 588365.061 ± 6489.866 | 635449.875 ± 11919.867 | **+8.0%** |
| MsgpackWriteBenchmark.writePojoJson | 1074511.310 ± 21590.608 | 1091652.218 ± 33939.472 | control |
| MsgpackWriteBenchmark.writePojoMsgpack | 694359.721 ± 23815.474 | 682673.869 ± 23781.724 | −1.7%, within error |
| WriteUTF8StringBenchmark.writeUTF8StringAscii | 95600.367 ± 675.393 | 102142.038 ± 900.010 | **+6.8%** |
| WriteUTF8StringBenchmark.writeUTF8StringNonAscii | 93577.160 ± 1595.829 | 100688.906 ± 6636.980 | **+7.6%** |

Read and raw string writes improved as expected from removing the msgpack-core buffer
hop. POJO write did not move: its cost is dominated by the node buffering that
MessagePack's counted container headers force on the generator, not by the byte copy.
That is the next thing to look at.

## Retained heap per idle thread

`./gradlew :jmh:threadLocalMemory --args="1024 1024 <mode>"`

| Mode | KB/thread |
|---|---|
| Stage A `both` | 8.49 |
| Stage B `both` | 137.00 |
| Stage B `vanilla` (plain JsonMapper, same payload) | 144.51 |
| Stage B `both`, 8 KB payload | 11.36 |

The Stage B figure is Jackson's own `BufferRecycler` pooling, not something this
library adds: `ByteArrayBuilder` releases its last block (up to `MAX_BLOCK_SIZE`, 128 KB,
for a 1 MB `writeValueAsBytes`) into the recycler, which keeps the largest buffer it is
given, and the default `ConcurrentDequePool` hands that recycler to the next caller.
Plain `JsonMapper` shows the same. Stage A looked cheaper only because its parser never
called `IOContext.close()`, so each parse dropped its recycler to GC instead of returning
it to the pool. Stage B closes the context as `ParserMinimalBase` does.
