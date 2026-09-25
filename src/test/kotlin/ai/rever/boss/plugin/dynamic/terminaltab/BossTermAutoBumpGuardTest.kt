package ai.rever.boss.plugin.dynamic.terminaltab

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BossTermAutoBumpGuardTest {
    @Test
    fun `auto bump pauses before network or mutations when ABI review is required`() = scenario(guarded = true)

    @Test
    fun `auto bump resumes when the reviewed upgrade removes the override guard`() = scenario(guarded = false)

    private fun scenario(guarded: Boolean) {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("bossterm-bump-test").toFile()
        try {
            val source = File(assertNotNull(System.getProperty("pluginProjectDir")), ".github/scripts/bump-bossterm.sh")
            val script = source.copyTo(File(root, "bump.sh"))
            val gradle = File(root, "build.gradle.kts")
            val original = "version = \"2.5.103\"\nval bosstermVersion = \"1.2.167\"\n" +
                if (guarded) "check(bosstermVersion == \"1.2.167\") { error(\"Review overrides\") }\n" else ""
            gradle.writeText(original)
            val bin = File(root, "bin").apply { mkdirs() }
            File(bin, "curl").apply {
                // Offline fixture: POM exists; release notes are not published yet.
                writeText("#!/bin/sh\nprintf called >> curl-called\n[ \"\$1\" = -fsI ]\n")
                setExecutable(true)
            }
            val output = File(root, "outputs")
            val process = ProcessBuilder("bash", script.absolutePath).directory(root).redirectErrorStream(true)
                .apply {
                    environment()["TARGET_VERSION"] = "1.2.168"
                    environment()["GITHUB_OUTPUT"] = output.absolutePath
                    environment()["PATH"] = bin.absolutePath + File.pathSeparator + environment()["PATH"]
                }.start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), log)
            if (guarded) {
                assertEquals(original, gradle.readText())
                assertEquals("changed=false\n", output.readText())
                assertFalse(File(root, "curl-called").exists())
                assertFalse(File(root, "pr-body.md").exists())
            } else {
                assertTrue(gradle.readText().contains("val bosstermVersion = \"1.2.168\""))
                assertTrue(gradle.readText().contains("version = \"2.5.104\""))
                assertTrue(output.readText().contains("changed=true"))
                assertTrue(File(root, "pr-body.md").exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
