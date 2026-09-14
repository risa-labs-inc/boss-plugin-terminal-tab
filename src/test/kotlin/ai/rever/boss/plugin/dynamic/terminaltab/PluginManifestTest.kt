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
        val compiled = System.getProperty("bossPluginApiVersion")
        assertEquals(compiled, minimum)
        // Equality also guarantees minApiVersion <= apiVersion.
        assertEquals(compiled, manifest["apiVersion"]?.jsonPrimitive?.content)

        val root = File(System.getProperty("pluginProjectDir"))
        for ((workflow, key) in listOf("build.yml" to "boss_plugin_api_version", "test.yml" to "API_VERSION")) {
            val text = File(root, ".github/workflows/$workflow").readText()
            val pin = Regex("(?m)^\\s*$key: ['\"]([^'\"]+)['\"]\\s*$").find(text)?.groupValues?.get(1)
            assertEquals(compiled, pin, "$workflow must compile against the declared API gate")
        }
    }
}
