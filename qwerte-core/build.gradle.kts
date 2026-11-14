plugins {
    `java-library`
}

group = "com.porter"
version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // No runtime dependencies on purpose: the library is shared by the addon, the plugin and the server.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.13.2") // only to write enchant-vectors.json from a test
    testImplementation("com.google.jimfs:jimfs:1.3.0") // test only: an in-memory POSIX file system for the farm key tests
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}
