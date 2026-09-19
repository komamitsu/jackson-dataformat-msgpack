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
