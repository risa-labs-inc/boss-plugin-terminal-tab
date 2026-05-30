import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
// 2.1.0 signals the new contract: plugin bundles bossterm-compose privately;
// host no longer carries it. Independent release cadence from the host.
version = "2.1.4"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

// BossTerm version is now private to this plugin. Bumping bossterm only
// requires re-releasing this plugin, not BossConsole.
val bosstermVersion = "1.1.96"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.37.jar"))
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // bossterm-compose is BUNDLED into the plugin JAR at runtime — it is NOT
    // on the host's classpath as of BossConsole 9.1.11. The plugin's
    // classloader resolves these classes from its own URLs; Compose Multiplatform
    // runtime classes still come from the host classloader (shared Compose
    // runtime — only one Window owner per JVM).
    implementation("com.risaboss:bossterm-compose:$bosstermVersion")

    // Compose dependencies — compileOnly so we don't duplicate the host's
    // Compose runtime in the plugin JAR. The plugin's @Composable functions
    // run inside the host's Compose runtime via classloader parent delegation.
    compileOnly(compose.desktop.currentOs)
    compileOnly(compose.runtime)
    compileOnly(compose.ui)
    compileOnly(compose.foundation)
    compileOnly(compose.material)
    compileOnly(compose.materialIconsExtended)

    // Decompose for ComponentContext — provided by host
    compileOnly("com.arkivanov.decompose:decompose:3.3.0")
    compileOnly("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines — provided by host
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

// Task to build plugin JAR with compiled classes + bossterm-compose bundled.
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-terminal-tab-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Terminal Tab Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.terminaltab.TerminalTabDynamicPlugin"
        )
    }

    // Include compiled classes
    from(sourceSets.main.get().output)

    // Include plugin manifest
    from("src/main/resources")

    // Bundle bossterm-compose + its transitive native-access deps (bossterm-core,
    // pty4j, JNA, ICU4J, purejavacomm). Compose Multiplatform / decompose /
    // kotlinx-coroutines / Material icons remain on the host classloader (the
    // plugin's classloader delegates to parent for them).
    //
    // Filename caveat: `com.risaboss:bossterm-compose-desktop` publishes its
    // JAR as `compose-ui-desktop-X.Y.Z.jar` (KMP target rename), so the filter
    // matches by content too. Since this plugin doesn't depend on
    // bosseditor-compose-desktop, there's no collision.
    from({
        configurations.runtimeClasspath.get().filter { jar ->
            val name = jar.name
            name.contains("bossterm-compose") ||
                name.contains("bossterm-core") ||
                name.startsWith("compose-ui-desktop-") ||  // bossterm-compose-desktop publishes under this name
                name.startsWith("pty4j-") ||
                name.startsWith("purejavacomm-") ||
                name.startsWith("jna-") ||
                name.startsWith("icu4j-") ||
                name.startsWith("trove4j-")
        }.map { zipTree(it) }
    })
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}
