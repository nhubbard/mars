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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.withForcedRespectIsShowingClientProperty
import com.intellij.util.ui.withShowingChanged
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunService
import edu.missouristate.mars.plugin.tools.BitmapDisplay.Base
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
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
        val (panel, statistics, branches, bitmap) = withContext(Dispatchers.EDT) {
            MarsToolsWindowFactory().createToolWindowContent(project, window)
            val contents = window.contentManager.contents
            assertEquals(listOf("Instruction Counter", "Instruction Statistics", "Branch History Table", "Bitmap Display"),
                contents.map { it.displayName })
            ComponentUtil.forceMarkAsShowing(container, true)
            withForcedRespectIsShowingClientProperty {
                UIUtil.dispatchAllInvocationEvents()
                withShowingChanged {
                    contents.forEach { container.add(it.component) }
                }
                UIUtil.dispatchAllInvocationEvents()
            }
            contents.map { it.component }
        }
        val selector = withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(panel, JComboBox::class.java).single() }
        val statsSelector = withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(statistics, JComboBox::class.java).single() }
        val branchSelector = withContext(Dispatchers.EDT) {
            UIUtil.findComponentsOfType(branches, JComboBox::class.java).single { it.itemCount == 0 }
        }
        val bitmapSelector = withContext(Dispatchers.EDT) {
            UIUtil.findComponentsOfType(bitmap, JComboBox::class.java).single { it.itemCount == 0 }
        }
        fun button(text: String, component: JComponent = panel) =
            UIUtil.findComponentsOfType(component, JButton::class.java).single { it.text == text }
        fun labels() = UIUtil.findComponentsOfType(panel, JBLabel::class.java).map { it.text }
        fun percentages() = UIUtil.findComponentsOfType(statistics, JProgressBar::class.java)
            .associate { it.accessibleContext.accessibleName to it.value }

        suspend fun waitFor(entries: Int, count: String) {
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    val nativeEntries = RunContentManager.getInstance(project).allDescriptors.count { it.processHandler is MarsProcessHandler }
                    assertEquals(entries, nativeEntries, "native execution descriptors")
                    assertEquals(entries, selector.itemCount)
                    assertEquals(entries, statsSelector.itemCount)
                    assertEquals(entries, branchSelector.itemCount)
                    assertEquals(entries, bitmapSelector.itemCount)
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

        suspend fun launch(executor: Executor, number: Long, source: String, captureBitmap: Boolean = false) = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("counter-$number.asm", source).virtualFile
            val process = project.getService(MarsRunService::class.java).createProcess(
                file.toNioPath(), memoryDumps = emptyList(), captureBitmap = captureBitmap,
            )
            val descriptor = RunContentDescriptor(null, process, JPanel(), "Counter sample").apply {
                executionId = number
                isActivateToolWindowWhenAdded = false
            }
            val content = addExecutionContent(executor, descriptor)
            process.startNotify()
            content
        }

        try {
            fun renderBitmap(): BufferedImage {
                val canvas = UIUtil.findComponentsOfType(bitmap, BitmapCanvas::class.java).single()
                canvas.setSize(512, 512)
                return BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB).also { image ->
                    val graphics = image.createGraphics()
                    try {
                        canvas.paint(graphics)
                    } finally {
                        graphics.dispose()
                    }
                }
            }
            @Suppress("UNCHECKED_CAST")
            fun baseChoice() = UIUtil.findComponentsOfType(bitmap, JComboBox::class.java)
                .single { it.itemCount > 0 && it.getItemAt(0) is Base } as JComboBox<Base>
            fun baseLabel(): String {
                val combo = baseChoice()
                return combo.renderer.getListCellRendererComponent(JList(), Base.STATIC_DATA, 2, false, false)
                    .accessibleContext.accessibleName
            }
            withContext(Dispatchers.EDT) {
                assertFalse(button("Connect").isEnabled)
                assertFalse(button("Reset").isEnabled)
                assertFalse(button("Apply and Reset", branches).isEnabled)
                assertFalse(button("Apply and Clear", bitmap).isEnabled)
                assertEquals("Static data", baseLabel())
                val unrelated = RunContentDescriptor(null, null, JPanel(), "Unrelated").apply {
                    isActivateToolWindowWhenAdded = false
                }
                addExecutionContent(run, unrelated)
            }
            val first = launch(run, 1, "addiu \$t0, \$zero, 7\n")
            waitFor(1, "1 (100%)")
            val firstBitmap = (first.getUserData(RunContentDescriptor.DESCRIPTOR_KEY)!!.processHandler as MarsProcessHandler).bitmapDisplay
            withContext(Dispatchers.EDT) {
                button("Connect", bitmap).doClick()
                assertTrue(firstBitmap.snapshot().connected)
            }
            val second = launch(debug, 2, "nop\nnop\n")
            waitFor(2, "1 (100%)")
            withContext(Dispatchers.EDT) {
                assertEquals(1, selector.selectedIndex)
                selector.selectedIndex = 0
            }
            waitFor(2, "2 (100%)")
            withContext(Dispatchers.EDT) {
                assertEquals(mapOf("ALU" to 100, "Jump" to 0, "Branch" to 0, "Memory" to 0, "Other" to 0), percentages())
                statsSelector.selectedIndex = 0
                button("Disconnect", statistics).doClick()
                button("Reset", statistics).doClick()
                assertEquals(setOf(0), percentages().values.toSet())
                assertTrue("2 (100%)" in labels())
                button("Connect", statistics).doClick()
                statsSelector.selectedIndex = 1
                assertEquals(100, percentages()["ALU"])
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
            assertFalse(firstBitmap.snapshot().connected)

            val bitmapRun = launch(
                run, 4, "lui \$t0, 0x1001\nlui \$t1, 0xff\nsw \$t1, 0(\$t0)\nli \$t1, 0xff\nsw \$t1, 4(\$t0)\n",
                captureBitmap = true,
            )
            waitFor(1, "5 (100%)")
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertEquals("0x10010000 (Static data)", baseLabel())
                    val image = renderBitmap()
                    assertEquals(0xff000000.toInt(), image.getRGB(0, 127))
                    assertEquals(0xffff0000.toInt(), image.getRGB(0, 128))
                    assertEquals(0xff0000ff.toInt(), image.getRGB(1, 128))
                }
            }
            withContext(Dispatchers.EDT) {
                baseChoice().selectedItem = Base.HEAP
                button("Disconnect", bitmap).doClick()
                button("Apply and Clear", bitmap).doClick()
                assertEquals(0xff000000.toInt(), renderBitmap().getRGB(0, 128))
                button("Connect", bitmap).doClick()
                button("Reset", bitmap).doClick()
                assertEquals(Base.HEAP, baseChoice().selectedItem)
                assertTrue(checkNotNull(bitmapRun.manager).removeContent(bitmapRun, true))
            }
            waitFor(0, "0 (0%)")

            val branchRun = launch(run, 3, "beq \$zero, \$zero, next\nnext: bne \$zero, \$zero, next\n")
            waitFor(1, "2 (100%)")
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    val table = UIUtil.findComponentsOfType(branches, JBTable::class.java).single()
                    val log = UIUtil.findComponentsOfType(branches, JBTextArea::class.java).single()
                    assertEquals(listOf("Index", "History", "Prediction", "Correct", "Incorrect", "Precision"),
                        (0 until table.columnCount).map(table::getColumnName))
                    assertEquals(listOf("0", "Taken", "Taken", "0", "1", "0.0%"),
                        (0 until table.columnCount).map { table.getValueAt(0, it) })
                    assertEquals("1", table.getValueAt(1, 3))
                    assertTrue(log.text.contains("predicted Not taken, actual Taken"))
                    assertTrue(log.text.contains("predicted Not taken, actual Not taken"))
                }
            }
            withContext(Dispatchers.EDT) {
                val table = UIUtil.findComponentsOfType(branches, JBTable::class.java).single()
                val choices = UIUtil.findComponentsOfType(branches, JComboBox::class.java)
                choices.single { it.itemCount == 3 }.selectedItem = 8
                choices.single { it.itemCount == 2 }.selectedItem = 2
                UIUtil.findComponentsOfType(branches, JBCheckBox::class.java).single().isSelected = true
                button("Disconnect", branches).doClick()
                button("Apply and Reset", branches).doClick()
                assertEquals(8, table.rowCount)
                assertEquals("Taken, Taken", table.getValueAt(0, 1))
                assertEquals("0", table.getValueAt(0, 4))
                button("Connect", branches).doClick()
                button("Reset", branches).doClick()
                assertEquals("Taken", table.getValueAt(0, 2))
                assertTrue(checkNotNull(branchRun.manager).removeContent(branchRun, true))
            }
            waitFor(0, "0 (0%)")
        } finally {
            withContext(Dispatchers.EDT) {
                window.contentManager.removeAllContents(true)
                withForcedRespectIsShowingClientProperty {
                    withShowingChanged {
                        container.remove(panel)
                        container.remove(statistics)
                        container.remove(branches)
                        container.remove(bitmap)
                    }
                }
            }
        }
    }
}
