package ai.rever.boss.plugin.dynamic.terminaltab

import ai.rever.bossterm.compose.window.LocalNativeWindowGlass
import ai.rever.bossterm.compose.window.LocalWindowGlassMode
import ai.rever.bossterm.compose.window.WindowGlassMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HostTerminalSurfaceTest {
    @Test
    fun `host glass reveals backdrop without fading content and restores opaque fallback`() = runComposeUiTest {
        val alpha = mutableStateOf(1f)
        var nativeGlass = false
        var mode = WindowGlassMode.OFF
        setContent {
            MaterialTheme(colors = darkColors(background = Color.White.copy(alpha = alpha.value))) {
                Box(Modifier.size(48.dp).background(Color.Red).testTag("surface")) {
                    HostTerminalSurface(Modifier.fillMaxSize(), color = Color.Black) {
                        val currentGlass = LocalNativeWindowGlass.current
                        val currentMode = LocalWindowGlassMode.current
                        SideEffect {
                            nativeGlass = currentGlass
                            mode = currentMode
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(8.dp).background(Color.Green))
                        }
                    }
                }
            }
        }
        fun verify(background: Color, installed: Boolean, expectedMode: WindowGlassMode) {
            val pixels = onNodeWithTag("surface").captureToImage().toPixelMap()
            assertEquals(background, pixels[2, 2])
            assertEquals(Color.Green, pixels[pixels.width / 2, pixels.height / 2])
            runOnIdle {
                assertEquals(installed, nativeGlass)
                assertEquals(expectedMode, mode)
            }
        }
        verify(Color.Black, false, WindowGlassMode.OFF)
        runOnIdle { alpha.value = 0.24f }
        verify(Color.Red, true, WindowGlassMode.WINDOW)
        runOnIdle { alpha.value = 1f }
        verify(Color.Black, false, WindowGlassMode.OFF)
    }
}
