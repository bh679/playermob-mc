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
// Mod-dep config name: obfuscated Loom creates modImplementation; the no-remap variant
// (deobfuscated 26.x) doesn't — mods need no remap there, so use plain implementation.
val modImpl = if (obfuscated) "modImplementation" else "implementation"
val common: Project = requireNotNull(stonecutter.node.sibling("")?.project) {
    "No common project for $project"
}

// Per-MC dependency coordinates.
val fabricApiVersion = when (mc) {
    "1.20.1" -> "0.92.9+1.20.1"
    "26.2"   -> "0.153.0+26.2"
    else     -> "0.103.0+1.21.1"
}
// Fabric loader: 26.2's Fabric API requires a newer loader than the 1.20.1/1.21.1 baseline.
val fabricLoaderVersion = when (mc) {
    "26.2" -> "0.19.3"
    else   -> prop("fabric_loader_version")!!
}
val minecraftRange = when (mc) {
    "1.20.1" -> "~1.20.1"
    "26.2"   -> "~26.2"
    else     -> "~1.21.1"
}
val javaLevel = when {
    stonecutter.eval(mc, ">=26")     -> 25
    stonecutter.eval(mc, ">=1.20.5") -> 21
    else                             -> 17
}
val javaMin = javaLevel.toString()

version = "${common.mod.version}+$mc"
base {
    archivesName.set("${common.mod.id}-$loader")
}
architectury {
    platformSetupLoomIde()
    fabric()
}

// MC 26.x ships deobfuscated — no Mojang mappings/license there. Obfuscated 1.20.1/1.21.1 need them.
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
    get("developmentFabric").extendsFrom(commonBundle)
}

repositories {
    mavenCentral()
    // Source for the opt-in compat-test stacks (Better Combat via `-PbmcCompatTest`, MusketMod via
    // `-PmusketCompatTest`). Only consulted when one of those flags is set.
    if (project.hasProperty("bmcCompatTest") || project.hasProperty("musketCompatTest")) {
        maven("https://api.modrinth.com/maven")
    }
}

dependencies {
    "minecraft"("com.mojang:minecraft:$mc")
    if (obfuscated) {
        // project.the<>() — inside dependencies{} the implicit ExtensionAware receiver is the
        // DependencyHandler (whose only extension is ext), so qualify to the Project to reach Loom.
        "mappings"(project.the<LoomGradleExtensionAPI>().officialMojangMappings())
    }
    modImpl("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImpl("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // `namedElements` is Loom's remap-namespace classpath variant — it only exists in the
    // obfuscated build. Under no-remap (deobfuscated 26.x) there's no remap namespace, so we
    // compile against the common project's default variant instead. `transformProduction*`
    // (the architectury common→loader bytecode transform) exists in both modes.
    if (obfuscated) {
        commonBundle(project(common.path, "namedElements")) { isTransitive = false }
    } else {
        commonBundle(project(common.path)) { isTransitive = false }
    }
    shadowBundle(project(common.path, "transformProductionFabric")) { isTransitive = false }

    // ----------------------------------------------------------------------
    // Opt-in compat-test stack: Better Combat + Player Animator + Better Mob
    // Combat + Mob Player Animator (+ Cloth Config). Verifies that PlayerMob
    // picks up Better Combat's animated melee in a real dev client. Enable with
    //   ./gradlew :fabric:1.20.1:runClient -PbmcCompatTest
    // OFF by default so normal dev runs aren't burdened with external mods.
    // `modRuntimeOnly` → dev RUNTIME only; never compiled against, never enters
    // the published jar, never a real PlayerMob dependency. Gated to MC 1.20.1,
    // the only target where all four are natively available (1.21.1 needs the
    // separate NeoForge-only "Better Mob Combat Neo" fork; 26.2 has none).
    // ----------------------------------------------------------------------
    if (mc == "1.20.1" && project.hasProperty("bmcCompatTest")) {
        // Better Combat 1.8.6, NOT the newer 1.9.0: Better Mob Combat 1.3.0 calls
        // net.bettercombat.compatibility.CompatibilityFlags.firstPersonRender(), which 1.9.0
        // removed — pairing 1.3.0 with 1.9.0 crashes (NoSuchMethodError) the first time any
        // mob plays a Better Combat attack animation. 1.8.6 is the newest BC that BMC 1.3.0
        // is binary-compatible with.
        "modRuntimeOnly"("maven.modrinth:better-combat:1.8.6+1.20.1-fabric")
        "modRuntimeOnly"("maven.modrinth:playeranimator:1.0.2-rc1+1.20-fabric")
        "modRuntimeOnly"("maven.modrinth:cloth-config:11.1.136+fabric")
        // Better Mob Combat + Mob Player Animator publish a separate Modrinth version
        // per loader that all share the same bare version_number (1.3.0 / 1.3.3), so
        // `…:better-mob-combat:1.3.0` is ambiguous and the Modrinth maven serves the
        // Forge jar (no fabric.mod.json → silently not loaded). Pin the exact Fabric
        // version IDs instead: wboV6qEY = bmc 1.3.0 fabric, suabW0rX = mpa 1.3.3 fabric.
        "modRuntimeOnly"("maven.modrinth:mob-player-animator:suabW0rX")
        "modRuntimeOnly"("maven.modrinth:better-mob-combat:wboV6qEY")
    }

    // ----------------------------------------------------------------------
    // Opt-in MusketMod compat test. Loads ewewukek's Musket Mod into the dev
    // client so a PlayerMob can be handed a `musketmod:musket` + cartridges and
    // verified firing real bullets (the `moddedRangedWeapons` feature). Enable:
    //   ./gradlew :fabric:1.21.1:runClient -PmusketCompatTest
    // `modRuntimeOnly` → dev RUNTIME only; never compiled against (the fire path
    // is fully generic — no MusketMod imports), never in the published jar. Gated
    // to MC 1.21.1, the only node MusketMod targets here. `qTBFjQQI` = the exact
    // Modrinth version id for MusketMod 1.5.4 Fabric 1.21.1 (bare version numbers
    // are ambiguous across loaders — see the Better-Mob-Combat note above).
    // ----------------------------------------------------------------------
    if (mc == "1.21.1" && project.hasProperty("musketCompatTest")) {
        "modRuntimeOnly"("maven.modrinth:musket-mod:qTBFjQQI")
    }
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
    exclude("architectury.common.json")
    configurations = listOf(shadowBundle)
    archiveClassifier = "dev-shadow"
}

// Obfuscated Loom remaps the shadowJar into the production jar (no classifier). The no-remap
// variant (deobfuscated 26.x) registers no remapJar/remapSourcesJar — the shadowJar IS the
// production artifact there, so it takes the empty classifier directly.
if (obfuscated) {
    tasks.named<RemapJarTask>("remapJar") {
        injectAccessWidener.set(true)
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
    expandProps(listOf("fabric.mod.json"),
        "version" to common.mod.version,
        "fabric_loader_version" to fabricLoaderVersion,
        "minecraft_range" to minecraftRange,
        "java_min" to javaMin,
        "mod_name" to common.mod.name,
        "mod_description" to common.mod.description,
        "mod_authors" to common.mod.authors,
        "mod_license" to common.mod.license,
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
