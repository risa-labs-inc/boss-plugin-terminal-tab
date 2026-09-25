package ai.rever.boss.plugin.dynamic.terminaltab

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurrentHostFontUtilsTest {
    @Test
    fun `shipped font settings and rendering work with Skia blocked`() {
        val jar = File(assertNotNull(System.getProperty("pluginJar")))
        val className = "ai.rever.bossterm.compose.util.FontUtilsKt"
        object : URLClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                synchronized(getClassLoadingLock(name)) {
                    if (name.startsWith("org.jetbrains.skia.") || name.startsWith("org.jetbrains.skiko.")) {
                        throw ClassNotFoundException(name)
                    }
                    if (name == className || name.startsWith(className + "$")) {
                        val result = findLoadedClass(name) ?: findClass(name)
                        if (resolve) resolveClass(result)
                        result
                    } else super.loadClass(name, resolve)
                }
        }.use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("org.jetbrains.skia.FontMgr") }
            val fonts = loader.loadClass(className)
            @Suppress("UNCHECKED_CAST")
            val categories = fonts.getMethod("getCategorizedFonts").invoke(null) as Map<String, List<String>>
            assertEquals(listOf("MesloLGS Nerd Font (Bundled)"), categories["Bundled"])
            val systemFonts = categories.getValue("Fixed Pitch") + categories.getValue("Variable Pitch")
            assertTrue(systemFonts.isNotEmpty())
            val load = fonts.getMethod("loadTerminalFont", String::class.java)
            val resolver = createFontFamilyResolver()
            for (name in listOf(null, systemFonts.first(), "BOSS missing font 123456")) {
                val family = load.invoke(null, name) as FontFamily
                assertNotNull(resolver.resolve(family).value)
            }
            // The optional symbol families may be absent on the OS, but must not lose
            // installed fonts merely because the plugin cannot access Skia directly.
            for ((family, getter) in listOf(
                "Apple Color Emoji" to "getCachedAppleColorEmojiFont",
                "STIX Two Math" to "getCachedSTIXMathFont",
            )) {
                val value = fonts.getMethod(getter).invoke(null) as FontFamily?
                if (systemFonts.contains(family) &&
                    (family != "Apple Color Emoji" || System.getProperty("os.name").contains("Mac"))) {
                    assertNotNull(value)
                }
                if (value != null) assertNotNull(resolver.resolve(value).value)
            }
        }
    }
}
