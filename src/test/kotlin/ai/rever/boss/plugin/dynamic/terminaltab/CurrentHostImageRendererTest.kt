package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.terminal.model.image.TerminalImage
import androidx.compose.ui.graphics.ImageBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CurrentHostImageRendererTest {
    @Test
    fun `shipped renderer decodes and caches images when direct Skia access is blocked`() {
        val jar = File(assertNotNull(System.getProperty("pluginJar")))
        val rendererName = "ai.rever.bossterm.compose.rendering.ImageRenderer"
        val parent = javaClass.classLoader
        ZipFile(jar).use { archive ->
            assertEquals(1, archive.entries().asSequence().count {
                it.name == rendererName.replace('.', '/') + ".class"
            })
            assertEquals(0, archive.entries().asSequence().count {
                it.name.startsWith("org/jetbrains/skia/") || it.name.startsWith("org/jetbrains/skiko/")
            })
        }
        // Load the shipped renderer child first and reproduce 9.5.25's Skia refusal.
        // Compose and the model types are shared with the test to inspect the result.
        object : URLClassLoader(arrayOf(jar.toURI().toURL()), parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                synchronized(getClassLoadingLock(name)) {
                    if (name.startsWith("org.jetbrains.skia.") || name.startsWith("org.jetbrains.skiko.")) {
                        throw ClassNotFoundException(name)
                    }
                    if (name == rendererName || name.startsWith(rendererName + "$")) {
                        val result = findLoadedClass(name) ?: findClass(name)
                        if (resolve) resolveClass(result)
                        result
                    } else {
                        super.loadClass(name, resolve)
                    }
                }
        }.use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("org.jetbrains.skia.Image") }
            val renderer = loader.loadClass(rendererName)
            val instance = renderer.getField("INSTANCE").get(null)
            val decode = renderer.getMethod("getOrDecodeImage", TerminalImage::class.java)
            val png = ByteArrayOutputStream().use { output ->
                ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "png", output)
                output.toByteArray()
            }
            val image = TerminalImage(data = png)
            val bitmap = assertNotNull(decode.invoke(instance, image)) as ImageBitmap
            assertEquals(2, bitmap.width)
            assertEquals(3, bitmap.height)
            assertSame(bitmap, decode.invoke(instance, image))
            renderer.getMethod("clearCachedImage", java.lang.Long.TYPE).invoke(instance, image.id)
            assertNull(decode.invoke(instance, image.copy(data = byteArrayOf(1, 2, 3))))
            renderer.getMethod("clearCache").invoke(instance)
        }
    }
}
