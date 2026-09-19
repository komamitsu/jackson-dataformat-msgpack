plugins {
    id("java")
    alias(libs.plugins.jmh)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    jmh(project(":"))
}

jmh {
    jmhVersion = libs.versions.jmh.asProvider()
    jvmArgs = listOf(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

// Not a JMH benchmark: a standalone main() that measures heap retained per idle thread.
tasks.register<JavaExec>("threadLocalMemory") {
    group = "benchmark"
    description = "Measures heap retained per idle thread after a generator or parser call."
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass = "org.komamitsu.jackson.dataformat.msgpack.benchmark.ThreadLocalMemoryBenchmark"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}
