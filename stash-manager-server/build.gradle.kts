plugins {
    java
    application
}

group = "com.porter"
version = "1.4.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.porter:qwerte-core")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.porter.stashmanager.StashManagerServer"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

// Runnable fat jar: build/libs/stash-manager-server.jar, start it with `java -jar`.
tasks.jar {
    archiveFileName = "stash-manager-server.jar"
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    }
}

// The page reads the enchantment tables as /enchant-<mc>.json: they are copied from qwerte-core's resources at build time, so the page
// and the planner can never differ (there are no copies in src/main/resources/web).
tasks.processResources {
    from("../qwerte-core/src/main/resources/com/porter/qwerte/core/enchant") {
        include("enchant-*.json")
        into("web")
    }
}
