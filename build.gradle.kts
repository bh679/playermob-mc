import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("maven-publish")
}

val mc = stonecutter.current.version
val java17 = stonecutter.eval(mc, "<1.20.5") // 1.20.1 → Java 17, 1.21.1 → Java 21

version = "${mod.version}+$mc"
group = mod.group
base {
    archivesName.set("${mod.id}-common")
}

// Architectury 'common' module — bytecode is transformed per loader at build time.
architectury.common("fabric", "forge", "neoforge")

loom {
    silentMojangMappingsLicense()
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    mappings(loom.officialMojangMappings())

    // Fabric loader provides the mixin/annotation deps + @Environment annotations used
    // by common code. We do NOT use other Fabric loader classes here.
    modImplementation("net.fabricmc:fabric-loader:${prop("fabric_loader_version")}")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    val javaVersion = if (java17) JavaVersion.VERSION_17 else JavaVersion.VERSION_21
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = if (java17) 17 else 21
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// Bake mod_version + the current git branch into a resource so the client-side
// version HUD (VersionInfo / VersionHudRenderer) can display them. Lives in
// `common` so the single generated file ships in ALL three loader jars.
// VersionHudRenderer hides the HUD only when branch == "main".
// ---------------------------------------------------------------------------
fun gitBranch(): String = try {
    fun run(vararg cmd: String): String {
        val proc = ProcessBuilder(*cmd)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        proc.waitFor()
        return proc.inputStream.bufferedReader().readText().trim()
    }
    val name = run("git", "rev-parse", "--abbrev-ref", "HEAD")
    if (name == "HEAD" || name.isEmpty()) run("git", "rev-parse", "--short", "HEAD").ifEmpty { "unknown" }
    else name
} catch (e: Exception) {
    logger.warn("Could not resolve git branch for version resource: ${e.message}")
    "unknown"
}

tasks.register<ProcessResources>("generateVersionProperties") {
    val replaceProperties = mapOf(
        "mod_version" to mod.version,
        "git_branch" to gitBranch(),
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    // Stonecutter keeps the single source of truth at the repo root (the node dirs
    // under versions/<ver>/ hold only build output), so read templates from there.
    from(rootProject.layout.projectDirectory.dir("src/main/templates"))
    into(layout.buildDirectory.dir("generated/sources/modMetadata"))
}
sourceSets["main"].resources.srcDir(tasks.named("generateVersionProperties"))

tasks.build {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
}

publishing {
    publications {
        create<MavenPublication>("mavenCommon") {
            artifactId = "${mod.id}-common"
            from(components["java"])
        }
    }
    repositories {
        maven { url = uri("file://${rootProject.projectDir}/repo") }
    }
}
