import org.gradle.language.jvm.tasks.ProcessResources
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("architectury-plugin")
    id("maven-publish")
}

val mc = stonecutter.current.version
val obfuscated = stonecutter.eval(mc, "<26")
// Deobfuscated MC 26.x uses the no-remap Loom variant (same jar, no mappings step). The Loom
// plugin is applied via apply() because the plugins{} block can't branch on the version — so
// Loom-specific calls below use typed APIs (configure<LoomGradleExtensionAPI>/the<>/"minecraft"/
// "mappings") since apply() doesn't generate the Kotlin DSL accessors.
apply(plugin = if (obfuscated) "dev.architectury.loom" else "dev.architectury.loom-no-remap")

// Mod-dependency configuration name. Obfuscated Loom creates the remapping configs
// (modImplementation/modCompileOnly/…); the no-remap variant (deobfuscated 26.x) skips
// them entirely — mods need no remap there, so they go on the plain implementation/
// compileOnly configs instead.
val modImpl = if (obfuscated) "modImplementation" else "implementation"

// Per-MC Java level: 1.20.1 → 17, 1.21.1 → 21, 26.2 → 25 (MC 26.x requires Java 25).
val javaLevel = when {
    stonecutter.eval(mc, ">=26")     -> 25
    stonecutter.eval(mc, ">=1.20.5") -> 21
    else                             -> 17
}

version = "${mod.version}+$mc"
group = mod.group
base {
    archivesName.set("${mod.id}-common")
}

// Architectury 'common' module — bytecode is transformed per loader at build time.
architectury.common("fabric", "forge", "neoforge")

// MC 26.x ships deobfuscated (no Mojang mappings exist) — the mappings line + license
// accept must be omitted there. Obfuscated versions (1.20.1/1.21.1) still need them.
if (obfuscated) {
    configure<LoomGradleExtensionAPI> {
        silentMojangMappingsLicense()
    }
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases/")
}

dependencies {
    "minecraft"("com.mojang:minecraft:$mc")
    if (obfuscated) {
        // project.the<>() — inside dependencies{} the implicit ExtensionAware receiver is the
        // DependencyHandler (whose only extension is ext), so qualify to the Project to reach Loom.
        "mappings"(project.the<LoomGradleExtensionAPI>().officialMojangMappings())
    }

    // Fabric loader provides the mixin/annotation deps + @Environment annotations used
    // by common code. We do NOT use other Fabric loader classes here. String-form config
    // name because Loom's accessors aren't generated when it's applied via apply().
    modImpl("net.fabricmc:fabric-loader:${prop("fabric_loader_version")}")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    val compat = JavaVersion.toVersion(javaLevel)
    sourceCompatibility = compat
    targetCompatibility = compat
    toolchain {
        // 1.20.1/1.21.1 stay on the validated JDK 21 toolchain (the release flag caps
        // bytecode at 17/21); 26.2 needs a JDK 25 toolchain (provisioned via the foojay
        // resolver in settings.gradle.kts).
        languageVersion = JavaLanguageVersion.of(if (javaLevel >= 25) 25 else 21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaLevel
}

tasks.test {
    useJUnitPlatform()
}

// The mixin compatibility level must match the per-version Java level — 1.20.1's
// bundled Mixin (Java 17) rejects a JAVA_21 compatibilityLevel at load time.
tasks.processResources {
    expandProps(listOf("playermob.mixins.json"),
        "mixin_compat" to "JAVA_$javaLevel",
    )
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
