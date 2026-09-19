# Stage C: encode directly into bytes instead of a node tree

Same machine, JVM and command as the earlier files. One column per step so each change's
effect is attributable. All numbers are ops/s, mean ± error over 10 iterations, 2 forks.

| Benchmark | Stage B | Step 1: writer hold mode | Step 2: direct encoding |
|---|---|---|---|
| readPojoJson | 674310 ± 7943 | 662179 ± 12455 | 695429 ± 10394 |
| readPojoMsgpack | 635450 ± 11920 | 590722 ± 74275 | 638354 ± 4817 |
| writePojoJson | 1091652 ± 33939 | 1067343 ± 30158 | 1084473 ± 11131 |
| writePojoMsgpack | 682674 ± 23782 | 699864 ± 9159 | **991796 ± 7045** |
| writeUTF8StringAscii | 102142 ± 900 | 103252 ± 1519 | 115701 ± 2283 |
| writeUTF8StringNonAscii | 100689 ± 6637 | 96774 ± 7785 | 110049 ± 1431 |

## Step 1: writer hold mode and header patching

Control run. The generator does not use `openContainer`/`closeContainer` yet, so this
measures whether adding the `holdDepth` check to the writer's hot path cost anything.
Writes are unchanged within error. `readPojoMsgpack` has an unusually wide error bar this
run (±74k against ±12k before); the reader was not touched, so that is machine noise.

## Step 2: the generator encodes on every call

The node tree is gone. Each `write*` encodes immediately into the writer; a container's
header is reserved on open and patched on close from `MessagePackWriteContext.getEntryCount()`.

`writePojoMsgpack` went from 700k to 992k ops/s, +42%, and now trails `writePojoJson` by
8.5% instead of 37%. Raw string writes gained 12 to 14% from skipping the wrapper object
and second pass. Read side unchanged, as expected.

Two behaviour changes came with this, both now the same as Jackson's own generators:
an `IllegalArgumentException` for an unrepresentable `BigDecimal` or `BigInteger` is raised
while the property is written, so databind wraps it in `DatabindException` with the path
(previously it escaped raw from `close()`); and closing a generator with open containers
finishes them when `AUTO_CLOSE_CONTENT` is on. `MessagePackWriteContext` also now tracks
nesting depth, so `StreamWriteConstraints.maxNestingDepth` is enforced on write.
