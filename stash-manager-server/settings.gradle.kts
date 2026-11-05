// Standalone build for the Stash Manager web server. It is separate from the
// root Fabric Loom build: run it with `./gradlew -p stash-manager-server build`.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "stash-manager-server"
