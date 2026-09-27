# One wrap helper for keys and container headers

Measured on the same machine as the earlier files, run back to back:

- Before: `c2d1248` (Write scalar values through one verify-and-wrap helper)
- After: `1af13b5`. Two commits on top of the before state: `f8695a4` (the change below) and
  a read-side change that builds repeated parser errors in one place, which no write benchmark
  runs

The JMH command of this run was not recorded, so it is not given here.

## Change

`f8695a4`: `writeName`, `openContainer` and `closeContainer` each wrapped their writer call in
their own try/catch that turned an `IOException` into a Jackson exception. The try/catch now
lives in one `pack()` helper, which makes those three hot methods small enough to inline
better.

## Result

| Benchmark | Before | After | Change |
|---|---|---|---|
| writePojoJson | 1099800 ± 14428 | 1088509 ± 13948 | control |
| writePojoMsgpack | 1170317 ± 9411 | **1273380 ± 12473** | **+8.8%** |

The error bars do not overlap and the control is flat.
