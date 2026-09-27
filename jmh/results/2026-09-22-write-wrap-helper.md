# One wrap helper for keys and container headers

Measured on the same machine as the earlier files, the commit and its parent run back to
back. The exact JMH options of this run were not recorded.

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
