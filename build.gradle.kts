import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // Compose dependencies (provided by host)
    compileOnly(compose.runtime)
    compileOnly(compose.foundation)
    compileOnly(compose.material)
    compileOnly(compose.ui)

    // Decompose (provided by host)
    compileOnly("com.arkivanov.decompose:decompose:3.3.0")
    compileOnly("com.arkivanov.essenty:lifecycle:2.5.0")

    // Plugin API - provides TabRegistry, TabInfo, etc.
    compileOnly("com.risaboss:plugin-api-desktop:1.0.11")

    // Plugin Tab Terminal - provides TerminalTabType and TerminalTabInfo
    compileOnly("com.risaboss:plugin-tab-terminal-desktop:1.0.0")
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Include resources
    from("src/main/resources")

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Terminal Tab Dynamic Plugin",
            "Implementation-Version" to project.version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.terminaltab.TerminalTabDynamicPlugin"
        )
    }
}
