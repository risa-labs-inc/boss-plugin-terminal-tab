import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.3"

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

// Flag to switch between local development and published dependencies
val useLocalDependencies = false
val bossConsolePath = "../../BossConsole"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development dependencies from BossConsole
        implementation(files("$bossConsolePath/plugins/plugin-api/build/libs/plugin-api-desktop-1.0.11.jar"))
        implementation(files("$bossConsolePath/plugins/plugin-ui-core/build/libs/plugin-ui-core-desktop-1.0.7.jar"))
        implementation(files("$bossConsolePath/plugins/plugin-bookmark-types/build/libs/plugin-bookmark-types-desktop-1.0.4.jar"))
        implementation(files("$bossConsolePath/plugins/plugin-workspace-types/build/libs/plugin-workspace-types-desktop-1.0.4.jar"))
        implementation(files("$bossConsolePath/plugins/plugin-api-browser/build/libs/plugin-api-browser-desktop-1.0.5.jar"))
    } else {
        // Plugin API from Maven Central (for release)
        implementation("com.risaboss:plugin-api-desktop:1.0.11")
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

tasks.build {
    dependsOn("buildPluginJar")
}
