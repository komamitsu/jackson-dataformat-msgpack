# Stage C: encode directly into bytes instead of a node tree

Same machine, JVM and command as the earlier files. One column per step so each change's
effect is attributable. All numbers are ops/s, mean ± error over 10 iterations, 2 forks.

| Benchmark | Stage B | Step 1: writer hold mode |
|---|---|---|
| readPojoJson | 674310 ± 7943 | 662179 ± 12455 |
| readPojoMsgpack | 635450 ± 11920 | 590722 ± 74275 |
| writePojoJson | 1091652 ± 33939 | 1067343 ± 30158 |
| writePojoMsgpack | 682674 ± 23782 | 699864 ± 9159 |
| writeUTF8StringAscii | 102142 ± 900 | 103252 ± 1519 |
| writeUTF8StringNonAscii | 100689 ± 6637 | 96774 ± 7785 |

## Step 1: writer hold mode and header patching

Control run. The generator does not use `openContainer`/`closeContainer` yet, so this
measures whether adding the `holdDepth` check to the writer's hot path cost anything.
Writes are unchanged within error. `readPojoMsgpack` has an unusually wide error bar this
run (±74k against ±12k before); the reader was not touched, so that is machine noise.
