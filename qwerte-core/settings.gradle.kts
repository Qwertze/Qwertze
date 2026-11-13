// Standalone build for the shared pure-Java library. It is separate from the
// root Fabric Loom build and from stash-manager-server: run it with
// `./gradlew -p qwerte-core build` (or `./gradlew build` inside this folder).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "qwerte-core"
