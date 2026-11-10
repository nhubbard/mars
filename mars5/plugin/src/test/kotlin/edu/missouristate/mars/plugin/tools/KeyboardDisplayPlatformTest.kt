package edu.missouristate.mars.plugin.tools

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.pollAssertionsAsync
import com.intellij.ui.ComponentUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.withForcedRespectIsShowingClientProperty
import com.intellij.util.ui.withShowingChanged
import edu.missouristate.mars.plugin.run.MarsRunService
import edu.missouristate.mars.plugin.tools.KeyboardDisplayDevice.Delay
import edu.missouristate.mars.plugin.tools.KeyboardDisplayDevice.Options
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class KeyboardDisplayPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)

    @Test
    fun `tool keyboard drives a real receiver and renders sequential and addressed output`() = timeoutRunBlocking<Unit>(20.seconds) {
        val project = projectFixture.get()
        val (view, process, content) = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("terminal.asm", """
                li ${'$'}v0, 12
                syscall
                lui ${'$'}t0, 0xffff
                lbu ${'$'}t1, 4(${'$'}t0)
                sb ${'$'}t1, 12(${'$'}t0)
                li ${'$'}v0, 12
                syscall
                lui ${'$'}t1, 0x00b0
                ori ${'$'}t1, ${'$'}t1, 0x0107
                sw ${'$'}t1, 12(${'$'}t0)
                nop
                nop
                li ${'$'}t1, 88
                sw ${'$'}t1, 12(${'$'}t0)
                li ${'$'}v0, 12
                syscall
            """.trimIndent()).virtualFile
            val view = KeyboardDisplayView(project)
            val container = JPanel()
            ComponentUtil.forceMarkAsShowing(container, true)
            withForcedRespectIsShowingClientProperty {
                withShowingChanged { container.add(view.component) }
                UIUtil.dispatchAllInvocationEvents()
            }
            val process = project.service<MarsRunService>().createProcess(file.toNioPath(), memoryDumps = emptyList())
            val descriptor = RunContentDescriptor(null, process, JPanel(), "Terminal sample").apply {
                executionId = 1
                isActivateToolWindowWhenAdded = false
            }
            val manager = ToolWindowManager.getInstance(project)
            val id = DefaultRunExecutor.getRunExecutorInstance().toolWindowId
            val window = manager.getToolWindow(id) ?: manager.registerToolWindow(RegisterToolWindowTask(id))
            val content = ContentFactory.getInstance().createContent(descriptor.component, descriptor.displayName, false)
            content.putUserData(RunContentDescriptor.DESCRIPTOR_KEY, descriptor)
            content.setDisposer(descriptor)
            window.contentManager.addContent(content)
            process.startNotify()
            Triple(view, process, content)
        }
        fun button(label: String) = UIUtil.findComponentsOfType(view.component, JButton::class.java).single { it.text == label }
        fun labels() = UIUtil.findComponentsOfType(view.component, JBLabel::class.java).map { it.text }
        val editor = withContext(Dispatchers.EDT) {
            EditorFactory.getInstance().allEditors.single { SwingUtilities.isDescendingFrom(it.component, view.component) }
        }
        try {
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertTrue(button("Connect").isEnabled)
                    assertTrue("MMIO base 0xffff0000" in labels())
                }
            }
            withContext(Dispatchers.EDT) {
                val keyboard = view.inputComponent
                val fields = UIUtil.findComponentsOfType(view.component, JBTextField::class.java).filter { it !== keyboard }
                val (columns, rows) = fields
                assertTrue("Display · 80 × 24" in labels())
                columns.text = "invalid"
                button("Resize and Reset").doClick()
                columns.text = "0"
                button("Resize and Reset").doClick()
                columns.text = "257"
                button("Resize and Reset").doClick()
                columns.text = "12"
                rows.text = "invalid"
                button("Resize and Reset").doClick()
                rows.text = "0"
                button("Resize and Reset").doClick()
                rows.text = "129"
                button("Resize and Reset").doClick()
                assertTrue("Display · 80 × 24" in labels())
                rows.text = "4"
                button("Resize and Reset").doClick()
                assertTrue("Display · 12 × 4" in labels())
                fun type(character: Char): Boolean {
                    val event = KeyEvent(keyboard, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, character)
                    keyboard.keyListeners.forEach { it.keyTyped(event) }
                    return event.isConsumed
                }
                assertFalse(type('X'))
                button("Connect").doClick()
                assertTrue(type('M'))
                val delay = UIUtil.findComponentsOfType(view.component, JComboBox::class.java).single { it.itemCount == 3 }
                delay.selectedItem = Delay.UNIFORM
                assertEquals(Delay.UNIFORM, process.keyboardDisplay.snapshot().options.delay)
                delay.selectedItem = Delay.FIXED
                UIUtil.findComponentsOfType(view.component, JSpinner::class.java).single().value = 2
                UIUtil.findComponentsOfType(view.component, JBCheckBox::class.java).single().doClick()
                assertEquals(Options(Delay.FIXED, 2, false), process.keyboardDisplay.snapshot().options)
            }
            process.processInput.write("x\n".toByteArray())
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertEquals("M", editor.document.text)
                    assertTrue(editor.settings.isUseSoftWraps)
                    assertTrue("Instructions until transmitter ready (1)" in labels())
                }
            }
            process.processInput.write("x\n".toByteArray())
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    val lines = editor.document.text.lines()
                    assertEquals(List(4) { 12 }, lines.map(String::length))
                    assertEquals('X', lines[1].last())
                    assertFalse(editor.settings.isUseSoftWraps)
                    assertEquals(26, editor.caretModel.offset)
                }
            }
            withContext(Dispatchers.EDT) {
                button("Reset").doClick()
                assertEquals("", editor.document.text)
                assertTrue("Display · 12 × 4" in labels())
                button("Disconnect").doClick()
                assertFalse(process.keyboardDisplay.snapshot().connected)
                button("Connect").doClick()
                assertTrue(process.keyboardDisplay.snapshot().connected)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) {
                Disposer.dispose(view)
                assertTrue(editor.isDisposed)
                assertFalse(process.keyboardDisplay.snapshot().connected)
                process.destroyProcess()
                content.manager!!.removeContent(content, true)
            }
        }
    }
}
