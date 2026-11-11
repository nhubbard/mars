package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.tools.toolsMessages
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

@TestApplication
class XRayDetailsPlatformTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun initializeSkia() {
            // Skia starts a JVM-wide reference cleaner when it first creates a native surface.
            Surface.makeRasterN32Premul(1, 1).close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalJewelApi::class)
    @Test
    fun `X-Ray exposes complete instruction and signal text to Compose accessibility`() = timeoutRunBlocking<Unit>(20.seconds) {
        withContext(Dispatchers.EDT) {
            val empty = toolsMessages.getMessage("xray.noSteps")
            val description = mutableStateOf(empty)
            val details = xRayDetailsContent(description)
            val scene = ImageComposeScene(300, 160)
            try {
                scene.setContent { SwingBridgeTheme(content = details) }
                scene.render(0).close()
                fun renderedText() = scene.semanticsOwners.flatMap { it.rootSemanticsNode.text() }
                assertEquals(listOf(empty), renderedText())

                val instruction = "0x00400000 · 0x00000000 · nop\nop 000000\nHighlighted paths: PC"
                description.value = instruction
                scene.render(16_000_000).close()
                assertEquals(listOf(instruction), renderedText())
            } finally {
                scene.close()
            }
        }
    }

    private fun SemanticsNode.text(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.text() }
}
