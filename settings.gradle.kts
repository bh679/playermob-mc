pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"
}

stonecutter {
    centralScript = "build.gradle.kts"
    kotlinController = true
    create(rootProject) {
        // Root `src/` is the Architectury 'common' module. Each loader is a branch.
        // NOTE: 1.20.1 is added in a follow-up step — 1.21.1 lands first to prove the
        // Stonecutter migration reproduces today's build before any back-port.
        versions("1.21.1")
        vcsVersion = "1.21.1"
        branch("fabric")
        branch("forge")
        branch("neoforge")
    }
}

rootProject.name = "playermob-mc"
