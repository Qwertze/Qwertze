import java.util.Properties

plugins {
    alias(libs.plugins.fabric.loom)
}

// Which Minecraft version to build for: ./gradlew build -Pmc=1.21.4. Each one has a versions/<mc>.properties file.
val mc = (findProperty("mc") as String?) ?: "1.21.11"
val target = Properties().apply {
    val file = rootProject.file("versions/$mc.properties")
    require(file.exists()) { "No versions/$mc.properties, supported versions are in the versions folder." }
    file.reader().use { load(it) }
}
fun target(key: String) = target.getProperty(key)

base {
    archivesName = properties["archives_base_name"] as String
    version = "${libs.versions.mod.version.get()}+$mc"
    group = properties["maven_group"] as String
}

// Code that differs between Minecraft versions lives in src/<mc>/java
sourceSets {
    main {
        java.srcDir("src/$mc/java")
    }
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }

    // dekrom's Baritone fork isn't on a maven, so its API jar comes straight from the GitHub release
    exclusiveContent {
        forRepository {
            ivy {
                name = "dekrom-baritone"
                url = uri("https://github.com/dekrom/baritone/releases/download/")
                patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeGroup("dekrom") }
    }
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:${target("minecraft")}")
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)

    // Meteor
    modImplementation("meteordevelopment:meteor-client:${target("meteor")}")

    // Baritone (dekrom's fork, https://github.com/dekrom/baritone), optional at runtime
    modCompileOnly("dekrom:baritone-api-fabric:${target("baritone")}")

    // Tests of the pure classes (recipes, crafting plans, spot search); nothing of Minecraft is started
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    test {
        useJUnitPlatform()
    }

    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to target("minecraft")
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
