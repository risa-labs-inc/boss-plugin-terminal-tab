package ai.rever.boss.plugin.dynamic.terminaltab

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginManifestTest {
    @Test
    fun `processed manifest gate matches compile and workflow API pins`() {
        val resource = assertNotNull(javaClass.classLoader.getResource("META-INF/boss-plugin/plugin.json"))
        val manifest = Json.parseToJsonElement(resource.readText()).jsonObject
        assertEquals(
            assertNotNull(System.getProperty("pluginVersion"), "Run this test via Gradle"),
            manifest["version"]?.jsonPrimitive?.content,
            "processResources must substitute the project version",
        )
        assertTrue(assertNotNull(manifest["minBossVersion"]).jsonPrimitive.content.isNotBlank())
        val minimum = assertNotNull(manifest["minApiVersion"]).jsonPrimitive.content
        assertTrue(minimum.isNotBlank(), "A missing gate offers the plugin to incompatible hosts")
        val compiled = assertNotNull(System.getProperty("bossPluginApiVersion"), "Run this test via Gradle")
        assertEquals(compiled, minimum, "minApiVersion must match the compiled API floor")
        // Equality also guarantees minApiVersion <= apiVersion.
        assertEquals(compiled, manifest["apiVersion"]?.jsonPrimitive?.content, "apiVersion must match the compile pin")

        val root = File(assertNotNull(System.getProperty("pluginProjectDir"), "Run this test via Gradle"))
        for ((workflow, key) in listOf("build.yml" to "boss_plugin_api_version", "test.yml" to "API_VERSION")) {
            val file = File(root, ".github/workflows/$workflow")
            assertTrue(file.isFile, "Required API workflow is missing: $file")
            val text = file.readText()
            val pins = Regex("(?m)^\\s*$key:([^\\r\\n]*)$").findAll(text).map {
                it.groupValues[1].substringBefore('#').trim().removeSurrounding("\"").removeSurrounding("'")
            }.toList()
            assertEquals(listOf(compiled), pins, "$workflow must declare exactly one API pin matching the gate (no duplicates)")
        }
    }
}
