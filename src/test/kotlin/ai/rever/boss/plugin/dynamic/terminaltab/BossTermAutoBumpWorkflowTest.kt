package ai.rever.boss.plugin.dynamic.terminaltab

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BossTermAutoBumpWorkflowTest {
    @Test
    fun `successful validation merges and dispatches the release`() = scenario("success")

    @Test
    fun `failed validation opens a draft without merging or releasing`() = scenario("failure")

    @Test
    fun `skipped validation opens a draft without merging or releasing`() = scenario("skipped")

    @Test
    fun `dry run opens a reviewed PR without merging or releasing`() = scenario("success", dryRun = true)

    @Test
    fun `an existing bump PR prevents another branch PR or release`() = scenario("success", existing = true)

    @Test
    fun `both jobs reject regeneration that exits successfully without bumping`() = regenerationScenario("1.2.180", "2.5.113", succeeds = false)

    @Test
    fun `both jobs reject regeneration with a different plugin version`() = regenerationScenario("1.2.181", "2.5.113", succeeds = false)

    @Test
    fun `both jobs accept regeneration of the exact prepared versions`() = regenerationScenario("1.2.181", "2.5.114", succeeds = true)

    private fun workflowScripts(stepName: String): List<String> {
        val project = File(assertNotNull(System.getProperty("pluginProjectDir")))
        val lines = File(project, ".github/workflows/bossterm-autobump.yml").readLines()
        return lines.indices.filter { lines[it].trim() == "- name: $stepName" }.map { step ->
            lines.drop(step + 1).takeWhile { it.isBlank() || it.startsWith("        ") }
                .dropWhile { it.trim() != "run: |" }.drop(1)
                .takeWhile { it.isBlank() || it.startsWith("          ") }
                .joinToString("\n").trimIndent().also {
                    assertTrue(it.isNotBlank(), "$stepName shell block not found")
                }
        }.also { assertTrue(it.isNotEmpty(), "$stepName step not found in workflow") }
    }

    private fun regenerationScenario(bossTerm: String, plugin: String, succeeds: Boolean) {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val scripts = workflowScripts("Recreate pinned bump")
        assertEquals(2, scripts.size, "Validation and publication must each verify regeneration")
        for (script in scripts) {
            val root = Files.createTempDirectory("bossterm-regeneration-test").toFile()
            try {
                File(root, "build.gradle.kts").writeText("val bosstermVersion = \"$bossTerm\"\nversion = \"$plugin\"\n")
                val bin = File(root, "bin").apply { mkdirs() }
                // Reproduce the bump script's successful noop on a transient POM failure.
                File(bin, "bash").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
                val entrypoint = File(root, "regenerate.sh").apply { writeText(script) }
                val process = ProcessBuilder("/bin/bash", entrypoint.absolutePath)
                    .directory(root).redirectErrorStream(true).apply {
                        environment().apply {
                            put("PATH", bin.absolutePath + File.pathSeparator + get("PATH"))
                            put("TARGET_VERSION", "1.2.181")
                            put("EXPECTED_PLUGIN", "2.5.114")
                        }
                    }.start()
                val log = process.inputStream.bufferedReader().readText()
                assertEquals(succeeds, process.waitFor() == 0, log)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun scenario(outcome: String, dryRun: Boolean = false, existing: Boolean = false) {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("bossterm-workflow-test").toFile()
        try {
            val script = workflowScripts("Open, merge & release").single()
            val entrypoint = File(root, "release.sh").apply { writeText(script) }
            val bin = File(root, "bin").apply { mkdirs() }
            File(bin, "gh").apply {
                writeText("""
                    #!/usr/bin/env bash
                    printf '%s\n' "${'$'}*" >> "${'$'}GH_CALLS"
                    if [ "${'$'}1 ${'$'}2" = 'pr list' ] && [ "${'$'}EXISTING_PR" = true ]; then
                      printf '123\n'
                    fi
                    exit 0
                """.trimIndent() + "\n")
                setExecutable(true)
            }
            File(bin, "git").apply {
                writeText("#!/usr/bin/env bash\nprintf '%s\\n' \"\$*\" >> \"\$GIT_CALLS\"\n")
                setExecutable(true)
            }
            val body = File(root, "pr-body.md").apply { writeText("Dependency bump\n") }
            val ghCalls = File(root, "gh-calls")
            val gitCalls = File(root, "git-calls")
            val process = ProcessBuilder("bash", entrypoint.absolutePath)
                .directory(root).redirectErrorStream(true).apply {
                    environment().apply {
                        put("PATH", bin.absolutePath + File.pathSeparator + get("PATH"))
                        put("NEW_BT", "1.2.181")
                        put("NEW_PLUGIN", "2.5.114")
                        put("DRY_RUN", dryRun.toString())
                        put("VALIDATION_OUTCOME", outcome)
                        put("VALIDATION_URL", "https://example.test/actions/runs/123")
                        put("EXISTING_PR", existing.toString())
                        put("GH_CALLS", ghCalls.absolutePath)
                        put("GIT_CALLS", gitCalls.absolutePath)
                    }
                }.start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), log)
            val calls = ghCalls.readLines()
            val shouldRelease = outcome == "success" && !dryRun && !existing
            assertEquals(shouldRelease, calls.any { it.startsWith("pr merge ") }, calls.toString())
            assertEquals(shouldRelease, calls.any { it.startsWith("workflow run ") }, calls.toString())
            if (existing) {
                assertEquals(1, calls.size)
                assertFalse(gitCalls.exists())
                assertEquals("Dependency bump\n", body.readText())
            } else {
                val create = calls.single { it.startsWith("pr create ") }
                assertEquals(outcome != "success", "--draft" in create)
                if (outcome != "success") {
                    assertTrue(body.readText().contains("https://example.test/actions/runs/123"))
                    assertTrue(body.readText().contains(outcome))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
