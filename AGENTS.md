# AGENTS.md

Guidance for coding agents working in this repository. The design itself (write and read
paths, buffer ownership, map keys, the parser's accessor contract) is in `docs/DESIGN.md`;
read it before changing the generator, parser, writer or reader.

## Project

A Jackson 3 dataformat for MessagePack, `org.komamitsu:jackson-dataformat-msgpack`, with its
own MessagePack encoder and decoder. msgpack-core is a test dependency only, used as the
reference implementation in tests.

## Commands

```bash
./gradlew clean build                  # Compile, checkstyle, all tests (Java 17 toolchain)
./gradlew build -PtestJavaVersion=21   # Run the tests on another JDK, as CI does for 17, 21 and 24
./gradlew soakTest -Psoak.seconds=120  # Memory-growth soak tests, excluded from the normal build
./gradlew :jmh:jmhJar                  # Build the benchmark jar
```

Tests run with `-Dfile.encoding=windows-1252`, so a conversion that forgets to name a charset
fails in the tests.

## Rules

- **Round-trip safety comes first.** Whatever this library writes, it must be able to read
  back. A type that cannot round-trip is refused on write rather than written in a form that
  fails on read (see `UnreadableKeyGuard` and DESIGN.md 2.6).
- **Compatibility with the Jackson 2 module (`msgpack-jackson` in msgpack-java) is not a
  goal.** Do not keep or add behaviour only because that module had it.
- **Output after a serialization failure is the caller's to discard.** Do not add rollback or
  cleanup machinery for callers that catch the exception and keep using the generator.
- **Bug fixes come with a test that fails without the fix.** Assert exact values and bytes,
  not only types or non-null.
- **Benchmark changes to a hot path before pushing.** Build the change and `main` and run them
  back to back:
  `java -jar jmh/build/libs/jmh-jmh.jar <Benchmark> -f 5 -wi 5 -w 1 -i 10 -r 2`, adding
  `--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED`
  through `-jvmArgs`. Measure with a reused mapper; a benchmark that creates a mapper per
  operation measures nothing users see. Numbers from different days are not comparable on
  this machine, and a short run is not enough to call a change free.
- **Record meaningful results in `jmh/results/`**, one file per change, with the command, the
  compared commits and the JSON control.
- **Reviews** go through GitHub Copilot, requested with
  `gh api -X POST repos/komamitsu/jackson-dataformat-msgpack/pulls/<n>/requested_reviewers -f "reviewers[]=copilot-pull-request-reviewer[bot]"`.
  Check each finding against the code, with a test where it makes a claim about behaviour,
  before fixing or declining it.
