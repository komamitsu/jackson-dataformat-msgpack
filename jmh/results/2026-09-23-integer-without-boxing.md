# Reading an integer without boxing it

Same machine as the earlier files, with `-prof gc` for the allocation figures, run back to
back:

- Before: `d2d774e` (Name the context methods for what they do)
- After: `1037af9` below

The rest of the JMH command of this run was not recorded, so it is not given here.

## Change

`1037af9`: the parser boxed every integer it read into an `Integer` or `Long`. It now keeps the
value in a primitive field.

## Result

| Benchmark | Before | After |
|---|---|---|
| readPojoMsgpack allocation | 2056 B/op | **1912 B/op** |
| readPojoJson allocation (control) | 2096 B/op | 2096 B/op |

144 bytes per operation are gone. MessagePack read now allocates about 9% less than Jackson's JSON reader on the same POJO.

Throughput did not resolve on this machine (694079 ± 11508 before, 680121 ± 7908 after, with
the JSON control moving from 674k to 677k), so the allocation figure is the result here.
