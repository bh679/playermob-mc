@file:Suppress("UnstableApiUsage")

plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("com.gradleup.shadow")
}

val loader = prop("loom.platform")!!
val mc = stonecutter.current.version
val common: Project = requireNotNull(stonecutter.node.sibling("")?.project) {
    "No common project for $project"
}

val neoforgeVersion = when (mc) {
    "1.20.1" -> "20.1.247"
    else     -> "21.1.228"
}

version = "${common.mod.version}+$mc"
base {
    archivesName.set("${common.mod.id}-$loader")
}
architectury {
    platformSetupLoomIde()
    neoForge {
        platformPackage = "forge"
    }
}

// Accept the Mojang mappings license before officialMojangMappings() finalizes it.
loom {
    silentMojangMappingsLicense()
}

val commonBundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val shadowBundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

configurations {
    compileClasspath.get().extendsFrom(commonBundle)
    runtimeClasspath.get().extendsFrom(commonBundle)
    get("developmentNeoForge").extendsFrom(commonBundle)
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases/")
    // Modrinth maven — source of the optional Dungeon Train compile-only artifact.
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    mappings(loom.officialMojangMappings())
    "neoForge"("net.neoforged:neoforge:$neoforgeVersion")

    // --- Optional Dungeon Train integration (mod id `dungeontrain`, NeoForge-only) ---
    // Compile-only against DT's public API: NOT bundled and NOT a runtime requirement.
    // Gated at runtime by ModList.isLoaded("dungeontrain"). DT targets 1.21.1, so the
    // compile-only deps only attach on the 1.21.1 node (the 1.20.1 entry-class call
    // sites are version-guarded out of the back-port).
    if (stonecutter.eval(mc, ">=1.21.1")) {
        modCompileOnly("maven.modrinth:dungeon-train:${prop("dungeon_train_version")}") { isTransitive = false }
        // DT's ManagedShip API names JOML types in its signatures, so these must
        // resolve at compile time even though we call none of those methods.
        compileOnly("org.joml:joml:1.10.5")
        compileOnly("org.joml:joml-primitives:1.10.0")
    }

    commonBundle(project(common.path, "namedElements")) { isTransitive = false }
    shadowBundle(project(common.path, "transformProductionNeoForge")) { isTransitive = false }
}

loom {
    runConfigs.all {
        isIdeConfigGenerated = true
        runDir = "../../../run/$loader"
    }
}

java {
    withSourcesJar()
    val javaVersion = if (stonecutter.eval(mc, ">=1.20.5")) JavaVersion.VERSION_21 else JavaVersion.VERSION_17
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.shadowJar {
    exclude("fabric.mod.json", "architectury.common.json")
    configurations = listOf(shadowBundle)
    archiveClassifier = "dev-shadow"
}

tasks.remapJar {
    input = tasks.shadowJar.get().archiveFile
    archiveClassifier = null
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    archiveClassifier = "dev"
}

tasks.processResources {
    expandProps(listOf("META-INF/neoforge.mods.toml"),
        "version" to common.mod.version,
        "neoforge_version" to neoforgeVersion,
        "mod_license" to common.mod.license,
        "mod_name" to common.mod.name,
        "mod_description" to common.mod.description,
        "mod_authors" to common.mod.authors,
    )
}

tasks.build {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
}

tasks.register<Copy>("buildAndCollect") {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
    from(tasks.remapJar.get().archiveFile, tasks.remapSourcesJar.get().archiveFile)
    into(rootProject.layout.buildDirectory.dir("libs/${common.mod.version}/$loader"))
    dependsOn("build")
}
