package ai.rever.boss.plugin.dynamic.terminaltab

import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BossTermOverrideAbiTest {
    @Test
    fun `shipped source overrides preserve every upstream public member`() {
        val plugin = File(assertNotNull(System.getProperty("pluginJar"))).toURI().toURL()
        for (name in listOf(
            "ai.rever.bossterm.compose.util.FontUtilsKt",
            "ai.rever.bossterm.compose.rendering.ImageRenderer",
        )) {
            // Inspect the actual pinned dependency rather than maintaining a second API list.
            val upstream = javaClass.classLoader.getResources(name.replace('.', '/') + ".class")
                .toList().filter { it.protocol == "jar" }
                .map { (it.openConnection() as JarURLConnection).jarFileURL }
                .distinct().single { it.path.substringAfterLast('/').startsWith("compose-ui-desktop-") }
            isolatedClass(upstream, name).use { original ->
                isolatedClass(plugin, name).use { replacement ->
                    val expected = publicMembers(original.loadClass(name))
                    val actual = publicMembers(replacement.loadClass(name))
                    assertTrue(actual.containsAll(expected), "$name lost upstream members: ${expected - actual}")
                }
            }
        }
    }

    private fun isolatedClass(jar: URL, name: String): URLClassLoader =
        object : URLClassLoader(arrayOf(jar), javaClass.classLoader) {
            override fun loadClass(requested: String, resolve: Boolean): Class<*> =
                synchronized(getClassLoadingLock(requested)) {
                    if (requested == name || requested.startsWith(name + "$")) {
                        val result = findLoadedClass(requested) ?: findClass(requested)
                        if (resolve) resolveClass(result)
                        result
                    } else super.loadClass(requested, resolve)
                }
        }

    private fun publicMembers(type: Class<*>): Set<String> = buildSet {
        type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.forEach {
            add("method ${Modifier.isStatic(it.modifiers)} ${it.name}" +
                it.parameterTypes.joinToString(prefix = "(", postfix = ")") { parameter -> parameter.name } +
                ":${it.returnType.name}")
        }
        type.declaredFields.filter { Modifier.isPublic(it.modifiers) }.forEach {
            add("field ${Modifier.isStatic(it.modifiers)} ${it.name}:${it.type.name}")
        }
    }
}
