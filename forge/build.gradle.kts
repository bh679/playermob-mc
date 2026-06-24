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

val forgeVersion = when (mc) {
    "1.20.1" -> "1.20.1-47.4.20"
    else     -> "1.21.1-52.1.14"
}
// mods.toml metadata ranges, per MC version.
val minecraftRange = when (mc) {
    "1.20.1" -> "[1.20.1]"
    else     -> "[1.21.1]"
}
val forgeLoaderRange = when (mc) {
    "1.20.1" -> "[47,)"
    else     -> "[51,)"
}

version = "${common.mod.version}+$mc"
base {
    archivesName.set("${common.mod.id}-$loader")
}
architectury {
    platformSetupLoomIde()
    forge()
}

// Accept the Mojang mappings license before officialMojangMappings() finalizes it.
loom {
    silentMojangMappingsLicense()
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/")
    // Modrinth maven — source of the bundled Adventure Item Names jar (Jar-in-Jar).
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
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
    get("developmentForge").extendsFrom(commonBundle)
}

// Architectury Loom 1.13's Forge dev launch pulls in net.fabricmc:fabric-log4j-util
// (a shaded copy of terminalconsoleappender) via dev-launch-injector. Forge already
// bundles terminalconsoleappender, so both modules end up on the module path and JPMS
// rejects the configuration. Exclude the Fabric copy.
configurations.all {
    exclude(group = "net.fabricmc", module = "fabric-log4j-util")
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    mappings(loom.officialMojangMappings())
    "forge"("net.minecraftforge:forge:$forgeVersion")

    commonBundle(project(common.path, "namedElements")) { isTransitive = false }
    shadowBundle(project(common.path, "transformProductionForge")) { isTransitive = false }

    // --- Bundle Adventure Item Names (Jar-in-Jar) ---
    // Nest AIN's published Forge 1.21.1 jar so it auto-installs alongside PlayerMob.
    // `include` is Loom's JiJ config (Forge jarJar). Pinned via ain_version; 1.21.1-only.
    // Users disable it via AIN's own config or by removing the nested jar.
    if (mc == "1.21.1") {
        val ain = "maven.modrinth:adventureitemnames:${prop("ain_version")}+forge-1.21.1"
        "include"(ain) { isTransitive = false }
        // `include` nests AIN in the production jar but does NOT add it to the dev run
        // classpath, so also load it at dev runtime to verify the bundle in `runClient`.
        // Dev-only — modRuntimeOnly never affects the published jar.
        "modRuntimeOnly"(ain) { isTransitive = false }
    }
}

loom {
    forge {
        convertAccessWideners = true
    }
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
    expandProps(listOf("META-INF/mods.toml"),
        "version" to common.mod.version,
        "forge_version" to forgeVersion,
        "minecraft_range" to minecraftRange,
        "forge_loader_range" to forgeLoaderRange,
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
