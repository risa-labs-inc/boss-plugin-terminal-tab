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
// 2.3.4: bundles BossTerm 1.2.102 (command blocks, command palette, workflows,
// history search, session restore, left tab bar).
// 2.3.6: bundles BossTerm 1.2.104 (session sharing: self-hosted web viewer with
// device approval); adds zxing (QR in share dialog) to the bundle; wires the
// sharing lifecycle (SessionShareManager start/shutdown, onTabClosed) and
// surfaces approval requests as host toasts. Sharing defaults for the
// BossConsole profile: port 7700 (MCP owns 7677), remote mode "off".
// 2.3.8: bundles BossTerm 1.2.108 (E2E encryption for session sharing,
// Share-All-Windows scope, phone-grade web viewer UX).
// 2.3.10: bundles BossTerm 1.2.109 (web viewer: clickable links + touch text
// selection, Enter key + iOS soft-keyboard fixes for the on-screen key strip).
// 2.3.12: bundles BossTerm 1.2.111 (session sharing: one-click cloudflared
// install on Linux + "Fit host to my screen" embedder hook; CLI script now
// bundled under common/ so packaged apps can install the bossterm CLI).
// 2.3.15: auto-bumped bundled BossTerm to 1.2.112
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.112.md).
// 2.3.17: auto-bumped bundled BossTerm to 1.2.113
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.113.md).
// 2.3.19: auto-bumped bundled BossTerm to 1.2.114
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.114.md).
// 2.3.21: auto-bumped bundled BossTerm to 1.2.115
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.115.md).
// 2.3.23: auto-bumped bundled BossTerm to 1.2.116
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.116.md).
// 2.3.25: auto-bumped bundled BossTerm to 1.2.117
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.117.md).
// 2.3.27: auto-bumped bundled BossTerm to 1.2.118
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.118.md).
// 2.3.30: auto-bumped bundled BossTerm to 1.2.119
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.119.md).
// 2.3.32: auto-bumped bundled BossTerm to 1.2.120
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.120.md).
// 2.5.0: bridges plugin-contributed MCP tools onto the `boss` server — reads the
// host McpToolRegistry (boss-plugin-api 1.0.51) and mirrors each active plugin's
// tools onto the live MCP server via addTool/removeTool (see McpDynamicTools.kt),
// so `mcp__boss__*` gains/loses tools as plugins are enabled/disabled — and
// exposes McpServerController via registerPluginAPI (MCP server on/off +
// one-click CLI attach for the Plugin Manager's MCP tab; see
// McpServerControl.kt). No bundled-BossTerm change (still 1.2.120).
// 2.5.2: auto-bumped bundled BossTerm to 1.2.124
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.124.md).
// 2.5.4: auto-bumped bundled BossTerm to 1.2.125
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.125.md).
// 2.5.6: auto-bumped bundled BossTerm to 1.2.126
// (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.126.md).
version = "2.5.10"

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
// 1.2.126: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.126.md).
// 1.2.125: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.125.md).
// 1.2.124: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.124.md).
// 1.2.120: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.120.md).
// 1.2.119: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.119.md).
// 1.2.118: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.118.md).
// 1.2.117: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.117.md).
// 1.2.116: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.116.md).
// 1.2.115: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.115.md).
// 1.2.114: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.114.md).
// 1.2.113: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.113.md).
// 1.2.112: auto-bumped bundled BossTerm (release notes: https://github.com/kshivang/BossTerm/blob/main/docs/release-notes/v1.2.112.md).
// 1.2.111 bundles the bossterm CLI script under common/ so packaged apps can
// install the CLI (#287). 1.2.110 adds one-click cloudflared install on Linux
// and a "Fit host to my screen" embedder hook for session sharing — both
// session-sharing UX, no new Kotlin deps.
// 1.2.109 is web-viewer-only: clickable links + touch text selection, and
// Enter-key / iOS soft-keyboard fixes for the on-screen key strip (no new
// Kotlin deps — viewer.js/index.html ship inside the bundled compose-ui jar).
// 1.2.108 adds end-to-end encryption for session sharing; 1.2.107 adds a
// "Share All Windows" scope, phone-grade web-viewer UX, and window-fit
// reconciliation.
// 1.2.106 adds remote-session connection (mirror a remote BossTerm's tabs as
// local tabs); 1.2.105 adds live remote-mode switching, verified Cloudflare
// links, and viewer parity (resize/splits/focus/tabs).
// 1.2.104 adds session sharing (1.2.103 launch + 1.2.104 polish): a Ktor-hosted
// web viewer (xterm.js) with device-approval handshake, QR share dialog (new
// transitive dep com.google.zxing:core — bundled below), and optional remote
// exposure (Tailscale/Cloudflare). New ktor-client/server-websockets deps are
// covered by the existing `ktor-` bundle prefix. 1.2.102 added command blocks,
// command palette, workflows, history search, session restore; compose-ui
// compiles with -Xjvm-default=all (no $DefaultImpls bridges). 1.1.101 added
// the `bossterm.settings.dir` relocation hook this plugin relies on.
val bosstermVersion = "1.2.126"

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
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.55.jar"))
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
                name.startsWith("config-") ||
                // QR-code rendering in BossTerm 1.2.104's share dialog uses
                // com.google.zxing:core, whose JAR is named `core-<ver>.jar` —
                // a bare `core-` name prefix would collide with core-common-/
                // core-proto- style artifacts, so match the gradle-cache PATH
                // segment instead (version- and filename-independent; also
                // catches the transitive zxing:javase if it ever appears).
                // zxing is not host-shared, so it must be bundled child-first.
                jar.path.replace('\\', '/').contains("/com.google.zxing/")
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
