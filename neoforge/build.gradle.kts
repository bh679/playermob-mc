@file:Suppress("UnstableApiUsage")

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask
import net.fabricmc.loom.task.RemapSourcesJarTask

plugins {
    id("architectury-plugin")
    id("com.gradleup.shadow")
}

val loader = prop("loom.platform")!!
val mc = stonecutter.current.version
val obfuscated = stonecutter.eval(mc, "<26")
// Deobfuscated MC 26.x uses the no-remap Loom variant (same jar, no mappings step). The Loom
// plugin is applied via apply() because the plugins{} block can't branch on the version — so
// Loom-specific calls below use typed APIs (configure<LoomGradleExtensionAPI>/the<>/"minecraft"/
// "mappings"/named<RemapJarTask>) since apply() doesn't generate the Kotlin DSL accessors.
// architectury-plugin stays in plugins{} so its `architectury { }` + `tasks.shadowJar` accessors work.
apply(plugin = if (obfuscated) "dev.architectury.loom" else "dev.architectury.loom-no-remap")
val common: Project = requireNotNull(stonecutter.node.sibling("")?.project) {
    "No common project for $project"
}

val neoforgeVersion = when (mc) {
    "1.20.1" -> "20.1.247"
    "26.2"   -> "26.2.0.7-beta"
    else     -> "21.1.228"
}
val javaLevel = when {
    stonecutter.eval(mc, ">=26")     -> 25
    stonecutter.eval(mc, ">=1.20.5") -> 21
    else                             -> 17
}
val minecraftRange = when (mc) {
    "26.2" -> "[26.2,)"
    else   -> "[1.21.1]"
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

// MC 26.x ships deobfuscated — no Mojang mappings/license there. Obfuscated 1.21.1 needs them.
if (obfuscated) {
    configure<LoomGradleExtensionAPI> {
        silentMojangMappingsLicense()
    }
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
    "minecraft"("com.mojang:minecraft:$mc")
    if (obfuscated) {
        // project.the<>() — inside dependencies{} the implicit ExtensionAware receiver is the
        // DependencyHandler (whose only extension is ext), so qualify to the Project to reach Loom.
        "mappings"(project.the<LoomGradleExtensionAPI>().officialMojangMappings())
    }
    "neoForge"("net.neoforged:neoforge:$neoforgeVersion")

    // --- Optional Dungeon Train integration (mod id `dungeontrain`, NeoForge-only) ---
    // Compile-only against DT's public API: NOT bundled and NOT a runtime requirement.
    // Gated at runtime by ModList.isLoaded("dungeontrain"). DT targets 1.21.1 only, so the
    // compile-only deps attach on just that node (the 1.20.1 + 26.2 entry-class call sites
    // are version-guarded out).
    if (mc == "1.21.1") {
        "modCompileOnly"("maven.modrinth:dungeon-train:${prop("dungeon_train_version")}") { isTransitive = false }
        // DT's ManagedShip API names JOML types in its signatures, so these must
        // resolve at compile time even though we call none of those methods.
        compileOnly("org.joml:joml:1.10.5")
        compileOnly("org.joml:joml-primitives:1.10.0")

    }

    // `namedElements` is Loom's remap-namespace classpath variant — it only exists in the
    // obfuscated build. Under no-remap (deobfuscated 26.x) there's no remap namespace, so we
    // compile against the common project's default variant instead. `transformProduction*`
    // (the architectury common→loader bytecode transform) exists in both modes.
    if (obfuscated) {
        commonBundle(project(common.path, "namedElements")) { isTransitive = false }
    } else {
        commonBundle(project(common.path)) { isTransitive = false }
    }
    shadowBundle(project(common.path, "transformProductionNeoForge")) { isTransitive = false }
}

configure<LoomGradleExtensionAPI> {
    runConfigs.all {
        isIdeConfigGenerated = true
        runDir = "../../../run/$loader"
    }
}

java {
    withSourcesJar()
    val compat = JavaVersion.toVersion(javaLevel)
    sourceCompatibility = compat
    targetCompatibility = compat
    toolchain {
        languageVersion = JavaLanguageVersion.of(if (javaLevel >= 25) 25 else 21)
    }
}

tasks.shadowJar {
    exclude("fabric.mod.json", "architectury.common.json")
    configurations = listOf(shadowBundle)
    archiveClassifier = "dev-shadow"
}

// Obfuscated Loom remaps the shadowJar into the production jar (no classifier). The no-remap
// variant (deobfuscated 26.x) registers no remapJar/remapSourcesJar — the shadowJar IS the
// production artifact there, so it takes the empty classifier directly.
if (obfuscated) {
    tasks.named<RemapJarTask>("remapJar") {
        inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
        archiveClassifier.set(null as String?)
        dependsOn(tasks.shadowJar)
    }
    tasks.jar {
        archiveClassifier = "dev"
    }
} else {
    tasks.shadowJar {
        archiveClassifier = null as String?
    }
}

tasks.processResources {
    expandProps(listOf("META-INF/neoforge.mods.toml"),
        "version" to common.mod.version,
        "neoforge_version" to neoforgeVersion,
        "minecraft_range" to minecraftRange,
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
    // Obfuscated: collect the remapped jar + remapped sources. No-remap (26.x): those tasks
    // don't exist — collect the shadowJar (production artifact) + the plain sources jar.
    if (obfuscated) {
        from(
            tasks.named<RemapJarTask>("remapJar").flatMap { it.archiveFile },
            tasks.named<RemapSourcesJarTask>("remapSourcesJar").flatMap { it.archiveFile },
        )
    } else {
        from(
            tasks.shadowJar.flatMap { it.archiveFile },
            tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile },
        )
    }
    into(rootProject.layout.buildDirectory.dir("libs/${common.mod.version}/$loader"))
    dependsOn("build")
}
