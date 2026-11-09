package edu.missouristate.mars.plugin.tools

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.pollAssertionsAsync
import com.intellij.ui.ComponentUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.withForcedRespectIsShowingClientProperty
import com.intellij.util.ui.withShowingChanged
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunService
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class InstructionCounterPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)

    @Test
    fun `MARS Tools selects native executions and controls their counters independently`() = timeoutRunBlocking<Unit> {
        val project = projectFixture.get()
        val manager = ToolWindowManager.getInstance(project)
        val run = DefaultRunExecutor.getRunExecutorInstance()
        val debug = DefaultDebugExecutor.getDebugExecutorInstance()
        val container = JPanel()
        val window = withContext(Dispatchers.EDT) { manager.registerToolWindow(RegisterToolWindowTask("MARS Tools")) }
        val panel = withContext(Dispatchers.EDT) {
            MarsToolsWindowFactory().createToolWindowContent(project, window)
            val content = window.contentManager.contents.single()
            assertEquals("Instruction Counter", content.displayName)
            ComponentUtil.forceMarkAsShowing(container, true)
            withForcedRespectIsShowingClientProperty {
                UIUtil.dispatchAllInvocationEvents()
                withShowingChanged { container.add(content.component) }
                UIUtil.dispatchAllInvocationEvents()
            }
            content.component
        }
        val selector = withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(panel, JComboBox::class.java).single() }
        fun button(text: String) = UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == text }
        fun labels() = UIUtil.findComponentsOfType(panel, JBLabel::class.java).map { it.text }

        suspend fun waitFor(entries: Int, count: String) {
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    val nativeEntries = RunContentManager.getInstance(project).allDescriptors.count { it.processHandler is MarsProcessHandler }
                    assertEquals(entries, nativeEntries, "native execution descriptors")
                    assertEquals(entries, selector.itemCount)
                    assertTrue(count in labels(), labels().toString())
                }
            }
        }

        // RunContentManager skips opening execution tabs in unit-test mode.
        fun addExecutionContent(executor: Executor, descriptor: RunContentDescriptor) =
            ContentFactory.getInstance().createContent(descriptor.component, descriptor.displayName, false).also { content ->
                val executionWindow = manager.getToolWindow(executor.toolWindowId)
                    ?: manager.registerToolWindow(RegisterToolWindowTask(executor.toolWindowId))
                content.putUserData(RunContentDescriptor.DESCRIPTOR_KEY, descriptor)
                content.setDisposer(descriptor)
                executionWindow.contentManager.addContent(content)
            }

        suspend fun launch(executor: Executor, number: Long, source: String) = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("counter-$number.asm", source).virtualFile
            val process = project.getService(MarsRunService::class.java).createProcess(file.toNioPath(), memoryDumps = emptyList())
            val descriptor = RunContentDescriptor(null, process, JPanel(), "Counter sample").apply {
                executionId = number
                isActivateToolWindowWhenAdded = false
            }
            val content = addExecutionContent(executor, descriptor)
            process.startNotify()
            content
        }

        try {
            withContext(Dispatchers.EDT) {
                assertFalse(button("Connect").isEnabled)
                assertFalse(button("Reset").isEnabled)
                val unrelated = RunContentDescriptor(null, null, JPanel(), "Unrelated").apply {
                    isActivateToolWindowWhenAdded = false
                }
                addExecutionContent(run, unrelated)
            }
            val first = launch(run, 1, "addiu \$t0, \$zero, 7\n")
            waitFor(1, "1 (100%)")
            val second = launch(debug, 2, "nop\nnop\n")
            waitFor(2, "1 (100%)")
            withContext(Dispatchers.EDT) {
                assertEquals(1, selector.selectedIndex)
                selector.selectedIndex = 0
            }
            waitFor(2, "2 (100%)")
            withContext(Dispatchers.EDT) {
                @Suppress("UNCHECKED_CAST")
                val combo = selector as JComboBox<Any>
                val display = combo.renderer.getListCellRendererComponent(JList(), combo.selectedItem, -1, false, false)
                assertEquals("Counter sample (#2)", display.accessibleContext.accessibleName)
                button("Disconnect").doClick()
                button("Reset").doClick()
                assertEquals("Connect", button("Connect").text)
                assertTrue("2 (100%)" !in labels())
                button("Connect").doClick()
                assertTrue(button("Disconnect").isEnabled)
                assertTrue(checkNotNull(second.manager).removeContent(second, true))
            }
            waitFor(1, "1 (100%)")
            withContext(Dispatchers.EDT) { assertTrue(checkNotNull(first.manager).removeContent(first, true)) }
            waitFor(0, "0 (0%)")
        } finally {
            withContext(Dispatchers.EDT) {
                window.contentManager.removeAllContents(true)
                withForcedRespectIsShowingClientProperty { withShowingChanged { container.remove(panel) } }
            }
        }
    }
}
