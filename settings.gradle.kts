/*
 * Gradle settings for qupath-extension-workshop.
 * Uses the foojay toolchains resolver so Gradle can auto-provision a JDK
 * if one isn't already present.
 */

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "qupath-extension-workshop"
