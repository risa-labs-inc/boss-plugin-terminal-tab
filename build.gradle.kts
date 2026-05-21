import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "2.0.0"

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

// ─── Local dev paths to sibling repos ─────────────────────────────────────
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"
val microkernelRuntimePath = "../boss-microkernel-runtime"
val bossConsoleUpstream = "../../BossConsole/build/upstream-artifacts"

// ─── Version pins ─────────────────────────────────────────────────────────
// bossterm-core supplies the PTY + emulator + cell-grid engine. The Compose
// UI module (bossterm-compose) is intentionally NOT a dependency — terminal
// rendering moves to the host in the OOP architecture.
val bosstermVersion = "1.1.96"
val pty4jVersion = "0.13.9"
val bossIpcVersion = "1.1.0"
val microkernelRuntimeVersion = providers.gradleProperty("runtime.version").orElse("1.0.0").get()

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // ─── compileOnly — provided by microkernel runtime classpath at spawn ─
    //
    // The OOP plugin's child JVM is launched with boss-microkernel-runtime's
    // fatJar as its main class. That fatJar bundles boss-ipc, plugin-api-core,
    // and plugin-api-ipc, so the plugin only needs them at compile time. The
    // plugin's own JAR stays small and ships only plugin-specific classes.

    if (useLocalDependencies) {
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.37.jar"))
        compileOnly(files("$microkernelRuntimePath/build/libs/boss-microkernel-runtime-$microkernelRuntimeVersion.jar"))
        compileOnly(files("$bossConsoleUpstream/boss-ipc-$bossIpcVersion.jar"))
    } else {
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
        compileOnly(files("build/downloaded-deps/boss-microkernel-runtime.jar"))
        compileOnly(files("build/downloaded-deps/boss-ipc.jar"))
    }

    // ─── Bundled into the plugin JAR (child-classloader-only) ─────────────
    //
    // bossterm-core + pty4j are the engine. They are NOT provided by the
    // host (the whole point of the migration is to keep bossterm out of the
    // host classpath), so the plugin must bring them.

    implementation("com.risaboss:bossterm-core:$bosstermVersion")
    implementation("org.jetbrains.pty4j:pty4j:$pty4jVersion")

    // ─── Required to compile against gRPC stubs ───────────────────────────
    //
    // gRPC + protobuf-kotlin are bundled by boss-microkernel-runtime's fatJar
    // at runtime, but we need them on the compile classpath so the generated
    // TerminalServiceCoroutineImplBase resolves.

    compileOnly("io.grpc:grpc-stub:1.72.0")
    compileOnly("io.grpc:grpc-kotlin-stub:1.4.3")
    compileOnly("io.grpc:grpc-protobuf:1.72.0")
    compileOnly("com.google.protobuf:protobuf-java:4.31.1")
    compileOnly("com.google.protobuf:protobuf-kotlin:4.31.1")

    // Coroutines (also bundled by microkernel runtime)
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // SLF4J — provided by microkernel runtime at runtime; needed here so the
    // logger statements compile.
    compileOnly("org.slf4j:slf4j-api:2.0.17")
}

// ─── Plugin JAR — compiled classes only, deps come from runtime ─────────
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-terminal-tab-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Terminal Tab Plugin (OOP)",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.terminaltab.TerminalTabDynamicPlugin"
        )
    }

    from(sourceSets.main.get().output)
    from("src/main/resources")

    // Bundle bossterm-core + pty4j inside the plugin JAR so the child JVM's
    // BOSS_PLUGIN_CLASSPATH = this JAR is sufficient. The microkernel runtime
    // provides gRPC, protobuf, coroutines, boss-ipc — everything else.
    from({
        configurations.runtimeClasspath.get().filter { jar ->
            val name = jar.name
            name.contains("bossterm-core") ||
                name.startsWith("pty4j-") ||
                // pty4j drags purejavacomm + slf4j-related; bossterm-core
                // already drags slf4j-api. Include purejavacomm.
                name.startsWith("purejavacomm-")
        }.map { zipTree(it) }
    })
}

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
