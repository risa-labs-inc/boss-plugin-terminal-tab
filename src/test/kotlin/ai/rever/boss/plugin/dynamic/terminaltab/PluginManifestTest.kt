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
        val minimum = assertNotNull(manifest["minApiVersion"]).jsonPrimitive.content
        assertTrue(minimum.isNotBlank(), "A missing gate offers the plugin to incompatible hosts")
        val compiled = assertNotNull(System.getProperty("bossPluginApiVersion"), "Run this test via Gradle")
        assertEquals(compiled, minimum)
        // Equality also guarantees minApiVersion <= apiVersion.
        assertEquals(compiled, manifest["apiVersion"]?.jsonPrimitive?.content)

        val root = File(assertNotNull(System.getProperty("pluginProjectDir"), "Run this test via Gradle"))
        for ((workflow, key) in listOf("build.yml" to "boss_plugin_api_version", "test.yml" to "API_VERSION")) {
            val file = File(root, ".github/workflows/$workflow")
            assertTrue(file.isFile, "Required API workflow is missing: $file")
            val text = file.readText()
            val pins = Regex("(?m)^\\s*$key:([^\\r\\n]*)$").findAll(text).map {
                it.groupValues[1].substringBefore('#').trim().removeSurrounding("\"").removeSurrounding("'")
            }.toList()
            assertEquals(listOf(compiled), pins, "$workflow must compile against the declared API gate")
        }
    }
}
