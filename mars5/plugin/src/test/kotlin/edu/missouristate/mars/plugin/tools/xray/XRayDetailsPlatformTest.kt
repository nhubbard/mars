package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.plugin.tools.toolsMessages
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalJewelApi::class)
    @Test
    fun `the schematic can be measured at zero width before the tool window opens`() = timeoutRunBlocking<Unit>(20.seconds) {
        withContext(Dispatchers.EDT) {
            val frame = mutableStateOf(XRaySchematicFrame(XRayUnit.DATAPATH))
            val schematic = xRaySchematicContent(frame, mutableIntStateOf(0)) { error("The hidden schematic cannot be activated") }
            val scene = ImageComposeScene(320, 200)
            try {
                scene.setContent {
                    SwingBridgeTheme { Box(Modifier.size(0.dp, 200.dp)) { schematic() } }
                }
                scene.render(0).close()
                assertTrue(scene.semanticsOwners.flatMap { it.rootSemanticsNode.text() }.isEmpty())
            } finally {
                scene.close()
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalJewelApi::class)
    @Test
    fun `schematics draw live signals and expose the drill-down controls`() = timeoutRunBlocking<Unit>(30.seconds) {
        withContext(Dispatchers.EDT) {
            val main = XRayUnit.DATAPATH
            val frame = mutableStateOf(XRaySchematicFrame(main))
            val position = mutableIntStateOf(0)
            val content = xRaySchematicContent(frame, position) { frame.value = XRaySchematicFrame(it) }
            val scene = ImageComposeScene(1000, 574)
            try {
                scene.setContent { SwingBridgeTheme(content = content) }
                var time = 0L
                fun render() = scene.render(time.also { time += 16_000_000 })
                fun nodes() = scene.semanticsOwners.flatMap { it.rootSemanticsNode.flatten() }
                fun text() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                fun pixel() = render().use { image ->
                    requireNotNull(image.encodeToData()).use { png ->
                        ImageIO.read(ByteArrayInputStream(png.bytes)).getRGB(170, 362)
                    }
                }

                render().close()
                assertTrue("Instruction\nmemory" in text())
                assertTrue("PC" in text())
                val diagram = XRayDiagram.load(main)
                val instruction = XRayInstruction(StepEvent(0x00400000, 0, 1))
                val signals = diagram.signals(instruction.word, instruction.format)
                val label = signals.first { it.isLabel }
                frame.value = XRaySchematicFrame(main, diagram.wireSegments, signals, instruction)
                val inactiveWire = pixel()
                position.intValue = 20
                assertNotEquals(inactiveWire, pixel())
                position.intValue = label.startsAt + 1
                render().close()
                assertTrue(instruction.signalLabel(label.name) in text())
                position.intValue = signals.maxOf(XRaySignal::endsAt)
                render().close()
                assertFalse(instruction.signalLabel(label.name) in text())

                for ((target, labelText) in mapOf(
                    XRayUnit.REGISTERS to ("Registers" to "Register 32"),
                    XRayUnit.CONTROL to ("Control" to "MemToReg"),
                    XRayUnit.ALU_CONTROL to ("ALU\ncontrol" to "Op 3"),
                )) {
                    val action = nodes().mapNotNull { it.config.getOrNull(SemanticsActions.OnClick) }
                        .single { it.label == toolsMessages.getMessage("xray.diagram.open", labelText.first) }
                    assertTrue(action.action!!.invoke())
                    assertEquals(target, frame.value.unit)
                    render().close()
                    assertTrue(labelText.second in text(), text().toString())
                    frame.value = XRaySchematicFrame(main)
                    render().close()
                }
            } finally {
                scene.close()
            }
        }
    }

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }
}
