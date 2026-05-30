/*
 * QuPath Atölye (Workshop) extension — bundles the workshop's Groovy
 * scripts as one-click menu items.
 *
 * Build:    ./gradlew clean build
 * Output:   build/libs/qupath-extension-workshop-<version>.jar
 * Install:  drag the .jar onto QuPath's main window → restart QuPath
 *
 * Reference: https://github.com/qupath/qupath-extension-template
 */

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    java
    `java-library`
    // JavaFX modules (controls/graphics) — needed at compile time because
    // QuPath's GUI APIs return JavaFX types. JavaFX is bundled with QuPath
    // at runtime, so we keep this as compileOnly via the plugin's defaults.
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "io.github.sbalci"
version = "0.2.0-alpha3"
description = "QuPath atölyesi: hücre tespiti, IHC kantifikasyonu, tümör/stroma, cTCF — bir menü ile."

java {
    toolchain {
        // QuPath 0.6.0 is compiled for Java 21
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // SciJava hosts QuPath release and snapshot artifacts
    maven {
        url = uri("https://maven.scijava.org/content/repositories/releases")
    }
    maven {
        url = uri("https://maven.scijava.org/content/repositories/snapshots")
    }
}

val qupathVersion = "0.6.0"

javafx {
    version = "23"
    modules("javafx.controls", "javafx.graphics", "javafx.fxml")
    configuration = "compileOnly"
}

dependencies {
    // QuPath APIs — provided by the host application at runtime
    compileOnly("io.github.qupath:qupath-gui-fx:$qupathVersion")
    compileOnly("io.github.qupath:qupath-core:$qupathVersion")

    // Groovy — bundled with QuPath, so compileOnly
    compileOnly("org.apache.groovy:groovy:4.0.21")

    // Logging — QuPath provides slf4j
    compileOnly("org.slf4j:slf4j-api:2.0.13")
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

// Generates build-info.properties fresh on every build (timestamp computed at
// execution time, so it isn't frozen into Gradle's configuration cache).
// Output is placed under build/generated/build-info and added to the main
// resources so it ends up at the root of the JAR.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outDir = layout.buildDirectory.dir("generated/build-info")
    val ver = project.version.toString()
    outputs.dir(outDir)
    outputs.upToDateWhen { false }
    doLast {
        val ts = ZonedDateTime
            .now(ZoneId.of("Europe/Istanbul"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'TRT'"))
        val file = outDir.get().file("build-info.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("build.timestamp=$ts\nbuild.version=$ver\n", Charsets.UTF_8)
    }
}

sourceSets.main {
    resources.srcDir(generateBuildInfo.map { it.outputs.files })
}

tasks.jar {
    archiveBaseName.set("qupath-extension-workshop")
    manifest {
        attributes(
            "Implementation-Title"   to "QuPath Workshop Extension",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name"  to "io.github.sbalci.qupath.workshop"
        )
    }
}
