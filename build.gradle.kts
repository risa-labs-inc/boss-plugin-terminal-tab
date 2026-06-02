import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
// 2.1.0 signals the new contract: plugin bundles bossterm-compose privately;
// host no longer carries it. Independent release cadence from the host.
// 2.2.0: plugin now hosts the BossTerm MCP server ("bossconsole" endpoint),
// bundling ktor-server-cio + the MCP Kotlin SDK.
// 2.3.0: adds host-facing MCP tools (run_in_sidebar, cli) that drive the
// sidebar/Runner and boss:// deep-link verbs via BossTermMcpConfig.additionalTools.
version = "2.3.1"

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
// 1.1.101 adds the `bossterm.settings.dir` relocation hook + the MCP
// status-pill `displayName` label (BossTerm #268) that this plugin relies on.
val bosstermVersion = "1.1.101"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// NOTE: the bundled ktor stays at bossterm's native version (3.3.2 client /
// 3.2.3 server), which is what the MCP Kotlin SDK 0.8.3 is built against. The
// host (BossConsole) is pinned to ktor 3.4.3 + kotlinx-serialization 1.9.0
// (the stack the MCP SDK supports), so the bundled ktor's parent-first
// kotlinx-serialization resolves to a matching 1.9.x — no encodeToSink / sse
// signature skew. Do NOT force ktor to 3.5.x here: the MCP SDK only supports
// ktor <= 3.3.x (its sse() signature differs in 3.5).

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
                name.startsWith("trove4j-") ||
                // BossTerm's in-process MCP server (ai.rever.bossterm.compose.mcp.*)
                // runs on an embedded Ktor (CIO) server + the MCP Kotlin SDK.
                // These are `io.ktor.*` / `io.modelcontextprotocol.*` / `kotlinx.io.*`
                // packages — NOT in BossConsole's parent-first shared-package set,
                // so the plugin classloader resolves them child-first and they must
                // be bundled here. slf4j / kotlinx-serialization / kotlinx-coroutines
                // are deliberately omitted (parent-first; provided by the host).
                name.startsWith("ktor-") ||
                name.startsWith("kotlin-sdk-") ||
                name.startsWith("kotlinx-io-") ||
                // Transitive runtime deps of the MCP SDK + Ktor that are also
                // child-first (not host-shared) — without these the MCP server
                // dies at class-init with NoClassDefFoundError:
                //   kotlin-logging (io.github.oshai)      — MCP SDK logging
                //   kotlinx-collections-immutable          — MCP SDK
                //   kotlinx-datetime                       — MCP SDK
                //   atomicfu                               — Ktor/coroutines runtime
                //   typesafe config                        — Ktor server config
                name.startsWith("kotlin-logging") ||
                name.startsWith("kotlinx-collections-immutable") ||
                name.startsWith("kotlinx-datetime") ||
                name.startsWith("atomicfu") ||
                name.startsWith("config-")
        }.map { zipTree(it) }
    })
}

// Sync version from build.gradle.kts into plugin.json (single source of truth).
// Declare `version` as a task input so a version-only bump invalidates the task —
// otherwise processResources stays UP-TO-DATE (its file inputs are unchanged) and
// ships a stale plugin.json whose version disagrees with the JAR filename.
tasks.processResources {
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}
