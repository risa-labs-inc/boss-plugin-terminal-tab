import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.14"

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

// BossTerm version - must match host's version
val bosstermVersion = "1.0.91"

// Resolve BossTerm JAR from Gradle cache for local development
val bosstermJar = if (useLocalDependencies) {
    val gradleCachePath = "${System.getProperty("user.home")}/.gradle/caches/modules-2/files-2.1/com.risaboss/bossterm-compose-desktop/$bosstermVersion"
    fileTree(gradleCachePath) { include("**/*.jar"); exclude("**/*-sources.jar") }.files.firstOrNull()
} else {
    null
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.23.jar"))
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // BossTerm - compileOnly since it's provided by host classloader at runtime
    if (useLocalDependencies && bosstermJar != null) {
        compileOnly(files(bosstermJar))
    } else {
        // CI: resolve from Maven Central
        compileOnly("com.risaboss:bossterm-compose:$bosstermVersion")
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

// Task to build plugin JAR with compiled classes only
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
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}
