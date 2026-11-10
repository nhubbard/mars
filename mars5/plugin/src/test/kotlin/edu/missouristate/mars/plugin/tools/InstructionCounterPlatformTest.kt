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
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.withForcedRespectIsShowingClientProperty
import com.intellij.util.ui.withShowingChanged
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.mips.hardware.AccessNotice.READ
import edu.missouristate.mars.mips.hardware.AccessNotice.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunService
import edu.missouristate.mars.plugin.tools.DataCacheSimulator.Placement
import edu.missouristate.mars.plugin.tools.DataCacheSimulator.Replacement
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Base
import edu.missouristate.mars.plugin.tools.xray.XRayCanvas
import edu.missouristate.mars.plugin.tools.xray.XRayDiagram
import edu.missouristate.mars.plugin.tools.xray.XRayUnit
import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSpinner
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
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
        val panels = withContext(Dispatchers.EDT) {
            MarsToolsWindowFactory().createToolWindowContent(project, window)
            val contents = window.contentManager.contents
            assertEquals(listOf("Instruction Counter", "Instruction Statistics", "Branch History Table", "Bitmap Display",
                "Memory Reference Visualization", "MIPS X-Ray", "Data Cache Simulator", "Floating Point Representation",
                "Digital Lab Sim", "Keyboard and Display Simulator", "MarsBot", "Scavenger Hunt", "Introduction to Tools"),
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
        val (panel, statistics, branches, bitmap, references) = panels
        val xray = panels[5]
        val cache = panels[6]
        val floating = panels[7]
        val lab = panels[8]
        val marsBot = panels[10]
        val (floatSelector, floatRegister) = withContext(Dispatchers.EDT) {
            UIUtil.findComponentsOfType(floating, JComboBox::class.java).sortedBy { it.itemCount }
        }
        fun floatField(columns: Int) = UIUtil.findComponentsOfType(floating, JBTextField::class.java).single { it.columns == columns }
        fun floatLabels() = UIUtil.findComponentsOfType(floating, JBLabel::class.java).map { it.text }
        val cacheSelector = withContext(Dispatchers.EDT) {
            UIUtil.findComponentsOfType(cache, JComboBox::class.java).single { it.itemCount == 0 }
        }
        val selector = withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(panel, JComboBox::class.java).single() }
        val statsSelector = withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(statistics, JComboBox::class.java).single() }
        val branchSelector = withContext(Dispatchers.EDT) {
            UIUtil.findComponentsOfType(branches, JComboBox::class.java).single { it.itemCount == 0 }
        }
        val bitmapSelector = withContext(Dispatchers.EDT) {
            UIUtil.findComponentsOfType(bitmap, JComboBox::class.java).single { it.itemCount == 0 }
        }
        val referenceSelector = withContext(Dispatchers.EDT) {
            UIUtil.findComponentsOfType(references, JComboBox::class.java).single { it.itemCount == 0 }
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
                    assertEquals(entries, referenceSelector.itemCount)
                    assertEquals(entries, cacheSelector.itemCount)
                    assertEquals(entries, floatSelector.itemCount)
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

        suspend fun launch(
            executor: Executor, number: Long, source: String, captureGrids: Boolean = false, start: Boolean = true,
        ) = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("counter-$number.asm", source).virtualFile
            val process = project.getService(MarsRunService::class.java).createProcess(
                file.toNioPath(), memoryDumps = emptyList(), captureBitmap = captureGrids, captureReferences = captureGrids,
            )
            val descriptor = RunContentDescriptor(null, process, JPanel(), "Counter sample").apply {
                executionId = number
                isActivateToolWindowWhenAdded = false
            }
            val content = addExecutionContent(executor, descriptor)
            if (start) process.startNotify()
            content
        }

        try {
            fun xrayCanvas() = UIUtil.findComponentsOfType(xray, XRayCanvas::class.java).single()
            fun xrayChoices() = UIUtil.findComponentsOfType(xray, JComboBox::class.java)
            fun xrayHistory() = xrayChoices().single { it.itemCount > 0 && it.getItemAt(0) is StepEvent }
            fun xrayDetails() = UIUtil.findComponentsOfType(xray, JBTextArea::class.java).single().text
            fun canvas(panel: JComponent) = UIUtil.findComponentsOfType(panel, MemoryGridCanvas::class.java).single()
            fun tooltip(panel: JComponent, x: Int, y: Int): String? {
                val grid = canvas(panel)
                grid.setSize(512, 512)
                return grid.getToolTipText(MouseEvent(grid, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false))
            }
            fun renderBitmap(panel: JComponent = bitmap): BufferedImage {
                val canvas = canvas(panel)
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
                val blank = XRayCanvas { error("There is no diagram to open") }
                blank.setSize(1, 1)
                val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
                try { blank.paint(graphics) } finally { graphics.dispose() }
                blank.dispatchEvent(MouseEvent(blank, MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, false))
                assertFalse(button("Connect").isEnabled)
                assertFalse(button("Reset").isEnabled)
                assertFalse(button("Apply and Reset", branches).isEnabled)
                assertFalse(button("Apply and Clear", bitmap).isEnabled)
                assertFalse(button("Play animation", xray).isEnabled)
                assertFalse(button("Apply and Reset", cache).isEnabled)
                assertEquals(0, UIUtil.findComponentsOfType(cache, JBTable::class.java).single().rowCount)
                assertEquals("Static data", baseLabel())
                assertEquals(null, tooltip(references, 10, 10))
                assertFalse(floatRegister.isEnabled)
                fun enter(columns: Int, text: String) = floatField(columns).apply {
                    this.text = text
                    postActionEvent()
                }
                enter(16, "-1.5")
                assertEquals("0xbfc00000", floatField(12).text)
                assertTrue("(-1)^1 × 2^0 × 1.10000000000000000000000 (binary significand)" in floatLabels())
                enter(12, "7f800000")
                assertTrue("Infinity" in floatLabels())
                enter(12, "7fc00123")
                assertTrue("NaN" in floatLabels())
                assertTrue("Exponent bits are all ones. Fraction bits as hexadecimal: 0x00400123" in floatLabels())
                floatField(8).text = "0"
                enter(23, "1")
                assertEquals("0x00000001", floatField(12).text)
                assertTrue("Subnormal" in floatLabels())
                assertTrue("(-1)^0 × 2^-126 × 0.00000000000000000000001 (binary significand)" in floatLabels())
                enter(16, "invalid")
                assertTrue(floatLabels().any { it.startsWith("Enter a floating-point value") })
                assertEquals("0x00000001", floatField(12).text)
                button("Reset", floating).doClick()
                assertEquals("0x00000000", floatField(12).text)
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
                val cacheLogging = UIUtil.findComponentsOfType(cache, JBCheckBox::class.java).single()
                assertEquals(1, cacheSelector.selectedIndex)
                cacheLogging.doClick()
                cacheSelector.selectedIndex = 0
                assertFalse(cacheLogging.isSelected)
                cacheSelector.selectedIndex = 1
                assertTrue(cacheLogging.isSelected)
                assertEquals(1, selector.selectedIndex)
                selector.selectedIndex = 0
            }
            waitFor(2, "2 (100%)")
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    val executions = xrayChoices().filter { it.itemCount == 2 && it.getItemAt(0) !is StepEvent }
                    assertEquals(1, executions.size)
                    val execution = executions.single()
                    execution.selectedIndex = 0
                    val histories = xrayChoices().filter { it.itemCount == 2 && it.getItemAt(0) is StepEvent }
                    assertEquals(1, histories.size)
                    val history = histories.single()
                    assertEquals(2L, (history.selectedItem as StepEvent).stepCount())
                    history.selectedIndex = 0
                    assertTrue(xrayDetails().contains("0x00400000 · 0x00000000 · nop"))
                    assertTrue(xrayDetails().contains("Highlighted paths"))
                    val canvas = xrayCanvas()
                    canvas.setSize(1000, 574)
                    val image = BufferedImage(1000, 574, BufferedImage.TYPE_INT_RGB)
                    fun render() = image.createGraphics().let {
                        try { canvas.paint(it) } finally { it.dispose() }
                    }
                    fun click(x: Int, y: Int) = canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 1, false))
                    render()
                    assertEquals(Color.YELLOW.rgb, image.getRGB(170, 362))
                    val label = XRayDiagram.load(XRayUnit.DATAPATH).signals(0, null).first { it.isLabel }
                    canvas.position = label.startsAt + 1
                    render()
                    button("Reset animation", xray).doClick()
                    assertEquals(0, canvas.position)
                    render()
                    assertTrue(image.getRGB(170, 362) != Color.YELLOW.rgb)
                    button("Play animation", xray).doClick()
                    assertTrue(button("Pause animation", xray).isEnabled)
                    button("Pause animation", xray).doClick()
                    val unit = xrayChoices().single { it.selectedItem is XRayUnit }
                    click(-1, -1)
                    click(10, 10)
                    assertEquals(XRayUnit.DATAPATH, unit.selectedItem)
                    click(480, 353)
                    assertEquals(XRayUnit.REGISTERS, unit.selectedItem)
                    click(390, 220)
                    assertEquals(XRayUnit.REGISTERS, unit.selectedItem)
                    unit.selectedItem = XRayUnit.DATAPATH
                    click(390, 220)
                    assertEquals(XRayUnit.CONTROL, unit.selectedItem)
                    unit.selectedItem = XRayUnit.DATAPATH
                    click(595, 485)
                    assertEquals(XRayUnit.ALU_CONTROL, unit.selectedItem)
                    button("Reset animation", xray).doClick()
                    button("Play animation", xray).doClick()
                }
            }
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) { assertTrue(xrayCanvas().position > 0) }
            }
            val trace = (second.getUserData(RunContentDescriptor.DESCRIPTOR_KEY)!!.processHandler as MarsProcessHandler).xray
            trace.record(StepEvent(0x00400008, -1, 3))
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    @Suppress("UNCHECKED_CAST")
                    val history = xrayHistory() as JComboBox<StepEvent>
                    assertEquals(3, history.itemCount)
                    assertEquals(1L, history.selectedItem.let { (it as StepEvent).stepCount() })
                    history.selectedIndex = 0
                    val unknown = history.renderer.getListCellRendererComponent(JList(), history.getItemAt(2), 2, false, false)
                    assertTrue(unknown.accessibleContext.accessibleName.contains("Unknown instruction"))
                }
            }
            trace.rewind(0)
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) { assertFalse(button("Reset animation", xray).isEnabled) }
            }
            withContext(Dispatchers.EDT) {
                withForcedRespectIsShowingClientProperty {
                    withShowingChanged { container.remove(xray) }
                    UIUtil.dispatchAllInvocationEvents()
                }
            }
            withContext(Dispatchers.EDT) {
                withForcedRespectIsShowingClientProperty {
                    withShowingChanged { container.add(xray) }
                    UIUtil.dispatchAllInvocationEvents()
                }
            }
            trace.record(StepEvent(0x00400000, 0, 1))
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) { assertTrue(button("Play animation", xray).isEnabled) }
            }
            withContext(Dispatchers.EDT) {
                val execution = xrayChoices().single { it.itemCount == 2 && it.getItemAt(0) !is StepEvent }
                button("Disconnect", xray).doClick()
                assertFalse(trace.connected)
                assertTrue(button("Play animation", xray).isEnabled)
                trace.record(StepEvent(0x00400004, 0, 2))
                assertEquals(1, trace.snapshot().size)

                execution.selectedIndex = 1
                assertTrue(button("Disconnect", xray).isEnabled)
                assertTrue(xrayDetails().contains("addiu"))
                execution.selectedIndex = 0
                assertTrue(button("Connect", xray).isEnabled)
                assertEquals(1, xrayHistory().itemCount)
                button("Clear trace", xray).doClick()
                assertFalse(button("Clear trace", xray).isEnabled)
                assertFalse(button("Play animation", xray).isEnabled)
                assertFalse(trace.connected)
                button("Connect", xray).doClick()
                assertTrue(trace.connected)
                trace.record(StepEvent(0x00400008, 0, 3))
            }
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertTrue(button("Clear trace", xray).isEnabled)
                    assertEquals(3L, (xrayHistory().selectedItem as StepEvent).stepCount())
                }
            }
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
                captureGrids = true,
            )
            waitFor(1, "5 (100%)")
            val dataCache = (bitmapRun.getUserData(RunContentDescriptor.DESCRIPTOR_KEY)!!.processHandler as MarsProcessHandler).dataCache
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    val status = UIUtil.findComponentsOfType(cache, JBLabel::class.java).map { it.text }
                    assertTrue("128 bytes   |   Accesses 2   |   Hits 1   |   Misses 1" in status, status.toString())
                    assertTrue("50%" in status)
                    assertEquals(50, UIUtil.findComponentsOfType(cache, JProgressBar::class.java).single().value)
                }
            }
            withContext(Dispatchers.EDT) {
                val combos = UIUtil.findComponentsOfType(cache, JComboBox::class.java)
                val placement = combos.single { it.selectedItem is Placement }
                val replacement = combos.single { it.selectedItem is Replacement }
                combos.filter { it.itemCount == 12 }.forEach { it.selectedItem = 2 }
                placement.selectedItem = Placement.FULL
                replacement.selectedItem = Replacement.RANDOM
                button("Apply and Reset", cache).doClick()
                assertEquals(DataCacheSimulator.Options(2, 2, Placement.FULL, 2, Replacement.RANDOM), dataCache.snapshot().options)
                val logToggle = UIUtil.findComponentsOfType(cache, JBCheckBox::class.java).single()
                logToggle.doClick()
                assertTrue(dataCache.snapshot().logging)
                AccessOrigin.duringExecution<Unit, RuntimeException> {
                    dataCache.record(MemoryAccessNotice(WRITE, 0x10010000, 0))
                    dataCache.record(MemoryAccessNotice(READ, 0x10010004, 0))
                    dataCache.record(MemoryAccessNotice(WRITE, 0x10010008, 0))
                    dataCache.record(MemoryAccessNotice(READ, 0x10010010, 0))
                }
                button("Disconnect", cache).doClick()
                assertFalse(dataCache.snapshot().connected)
                val table = UIUtil.findComponentsOfType(cache, JBTable::class.java).single()
                assertEquals(listOf("Block", "Set", "Tag", "Last access", "Last result"),
                    (0 until table.columnCount).map(table::getColumnName))
                assertEquals(2, table.rowCount)
                assertEquals(listOf("0", "0", "0x02002000", "2", "Hit"),
                    (0 until table.columnCount).map { table.getValueAt(0, it) })
                assertEquals("Miss", table.getValueAt(1, 4))
                val log = UIUtil.findComponentsOfType(cache, JBTextArea::class.java).single()
                assertTrue(log.text.contains("Write 0x10010000 · tag 0x02002000 · set 0 · block 0 · Miss, filled empty block"))
                assertTrue(log.text.contains("Read 0x10010004 · tag 0x02002000 · set 0 · block 0 · Hit"))
                assertTrue(log.text.contains("Read 0x10010010 · tag 0x02002002 · set 0 · block 1 · Miss, replaced tag 0x02002001"))
                val beforeReplacementChange = dataCache.snapshot()
                replacement.selectedItem = Replacement.LRU
                assertEquals(beforeReplacementChange.copy(options = beforeReplacementChange.options.copy(replacement = Replacement.LRU)),
                    dataCache.snapshot())
                button("Reset", cache).doClick()
                assertEquals(listOf("0", "0", "", "", "Empty"),
                    (0 until table.columnCount).map { table.getValueAt(0, it) })
                assertEquals("", log.text)
                button("Connect", cache).doClick()
                logToggle.doClick()
                assertTrue(dataCache.snapshot().connected)
                assertFalse(dataCache.snapshot().logging)
            }
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertEquals("0x10010000 (Static data)", baseLabel())
                    val image = renderBitmap()
                    assertEquals(0xff000000.toInt(), image.getRGB(0, 127))
                    assertEquals(0xffff0000.toInt(), image.getRGB(0, 128))
                    assertEquals(0xff0000ff.toInt(), image.getRGB(1, 128))
                    assertEquals("0x10010000 · 1 accesses", tooltip(references, 10, 10))
                    assertEquals(0xff0000ff.toInt(), renderBitmap(references).getRGB(10, 10))
                }
            }
            withContext(Dispatchers.EDT) {
                assertEquals(null, tooltip(bitmap, 10, 128))
                assertEquals(null, tooltip(references, -1, 10))
                UIUtil.findComponentsOfType(references, JSpinner::class.java).single().value = 1
                UIUtil.findComponentsOfType(references, ColorPanel::class.java).single().selectedColor = Color.MAGENTA
                references.setSize(JBUI.scale(420), JBUI.scale(600))
                references.doLayout()
                val setColor = button("Set Color", references)
                assertTrue(setColor.width > 0 && setColor.x + setColor.width <= references.width)
                setColor.doClick()
                assertEquals(Color.MAGENTA.rgb, renderBitmap(references).getRGB(10, 10))
                val boundaries = UIUtil.findComponentsOfType(references, JBCheckBox::class.java).single()
                boundaries.doClick()
                val withGrid = renderBitmap(references)
                assertTrue(withGrid.getRGB(0, 10) != withGrid.getRGB(10, 10))
                boundaries.doClick()
                UIUtil.findComponentsOfType(references, JComboBox::class.java).single { it.itemCount == 12 }.selectedItem = 2
                button("Apply and Clear", references).doClick()
                assertEquals("0x10010008 · 0 accesses", tooltip(references, 33, 10))
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
                    assertEquals(listOf("Index", "History", "Prediction", "Correct", "Incorrect", "Precision", "Latest decision"),
                        (0 until table.columnCount).map(table::getColumnName))
                    assertEquals(listOf("0", "Taken", "Taken", "0", "1", "0.0%", ""),
                        (0 until table.columnCount).map { table.getValueAt(0, it) })
                    assertEquals("1", table.getValueAt(1, 3))
                    assertEquals("Correct", table.getValueAt(1, 6))
                    assertTrue(log.text.contains("predicted Not taken, actual Taken (Incorrect)"))
                    assertTrue(log.text.contains("predicted Not taken, actual Not taken (Correct)"))
                    val latest = UIUtil.findComponentsOfType(branches, JBLabel::class.java)
                        .single { it.text.startsWith("0x00400004 · bne") }
                    assertTrue(latest.text.endsWith(" · row 1"))
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
                assertEquals("", table.getValueAt(1, 6))
                assertTrue(UIUtil.findComponentsOfType(branches, JBLabel::class.java).any { it.text == "No branches recorded." })
                assertTrue(UIUtil.findComponentsOfType(branches, JBTextArea::class.java).single().text.isEmpty())
                button("Connect", branches).doClick()
                button("Reset", branches).doClick()
                assertEquals("Taken", table.getValueAt(0, 2))
                assertTrue(checkNotNull(branchRun.manager).removeContent(branchRun, true))
            }
            waitFor(0, "0 (0%)")
            val floatRun = launch(run, 5, "lui \$t0, 0x3fc0\nmtc1 \$t0, \$f0\n", start = false)
            waitFor(1, "0 (0%)")
            withContext(Dispatchers.EDT) {
                floatRegister.selectedIndex = 1
                assertTrue("Waiting for register data." in floatLabels())
                assertFalse(floatField(12).isEditable)
                floatRun.getUserData(RunContentDescriptor.DESCRIPTOR_KEY)!!.processHandler!!.startNotify()
            }
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) { assertEquals("0x3fc00000", floatField(12).text) }
            }
            withContext(Dispatchers.EDT) {
                assertEquals("1.5", floatField(16).text)
                assertFalse(button("Reset", floating).isEnabled)
                floatField(12).text = "ffffffff"
                floatField(12).postActionEvent()
                assertTrue("Attached registers are editable during a debugger pause." in floatLabels())
                floatRegister.selectedIndex = 0
                assertEquals("0x3fc00000", floatField(12).text)
                assertTrue(floatField(12).isEditable)
                button("Reset", floating).doClick()
                assertEquals("0x00000000", floatField(12).text)
                floatRegister.selectedIndex = 1
                assertEquals("0x3fc00000", floatField(12).text)
                floatRegister.selectedIndex = 0
                assertTrue(checkNotNull(floatRun.manager).removeContent(floatRun, true))
            }
            waitFor(0, "0 (0%)")
            val labRun = launch(run, 6, """
                li ${'$'}v0, 12
                syscall
                lui ${'$'}t0, 0xffff
                li ${'$'}t1, 0x3f
                sb ${'$'}t1, 0x10(${'$'}t0)
                li ${'$'}t1, 0x06
                sb ${'$'}t1, 0x11(${'$'}t0)
                li ${'$'}t1, 1
                sb ${'$'}t1, 0x13(${'$'}t0)
                li ${'$'}v0, 12
                syscall
            """.trimIndent())
            val labProcess = labRun.getUserData(RunContentDescriptor.DESCRIPTOR_KEY)!!.processHandler as MarsProcessHandler
            fun labPanels() = UIUtil.findComponentsOfType(lab, JPanel::class.java)
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) { assertTrue(button("Connect", lab).isEnabled) }
            }
            withContext(Dispatchers.EDT) {
                assertTrue(UIUtil.findComponentsOfType(lab, JToggleButton::class.java).none { it.isEnabled })
                button("Connect", lab).doClick()
            }
            labProcess.processInput.write("x\n".toByteArray())
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertTrue(labPanels().any { it.toolTipText == "Right display (0x3f)" })
                    assertTrue(labPanels().any { it.toolTipText == "Left display (0x06)" })
                    assertTrue(UIUtil.findComponentsOfType(lab, JBLabel::class.java)
                        .any { it.text == "28 instructions until next interrupt" })
                }
            }
            withContext(Dispatchers.EDT) {
                val right = labPanels().single { it.toolTipText == "Right display (0x3f)" }
                right.size = right.preferredSize
                val image = BufferedImage(right.width, right.height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                try { right.paint(graphics) } finally { graphics.dispose() }
                assertTrue(image.getRGB(JBUI.scale(20), JBUI.scale(7)) != image.getRGB(JBUI.scale(20), JBUI.scale(33)))
                val key = UIUtil.findComponentsOfType(lab, JToggleButton::class.java).single { it.text == "A" }
                lab.setSize(JBUI.scale(365), JBUI.scale(520))
                lab.doLayout()
                key.parent.doLayout()
                val lastKey = UIUtil.findComponentsOfType(lab, JToggleButton::class.java).single { it.text == "F" }
                val bounds = SwingUtilities.convertRectangle(lastKey.parent, lastKey.bounds, lab)
                assertTrue(bounds.width > 0 && bounds.x + bounds.width <= lab.width)
                key.doClick()
                assertEquals(10, labProcess.digitalLab.snapshot().key)
                assertTrue(key.isSelected)
                button("Reset", lab).doClick()
                assertFalse(key.isSelected)
                assertEquals("Right display (0x00)", right.toolTipText)
                button("Disconnect", lab).doClick()
                assertFalse(key.isEnabled)
                button("Connect", lab).doClick()
                assertTrue(labProcess.digitalLab.snapshot().connected)
                val content = window.contentManager.contents.single { it.component === lab }
                assertTrue(window.contentManager.removeContent(content, true))
                assertFalse(labProcess.digitalLab.snapshot().connected)
                labProcess.destroyProcess()
                assertTrue(checkNotNull(labRun.manager).removeContent(labRun, true))
            }
            waitFor(0, "0 (0%)")
            fun robotClock() = UIUtil.findComponentsOfType(marsBot, JBTextField::class.java).single()
            fun robotLabels() = UIUtil.findComponentsOfType(marsBot, JBLabel::class.java)
            fun robotBoard() = UIUtil.findComponentsOfType(marsBot, MarsBotBoard::class.java).single()
            fun paintRobot(width: Int, height: Int): BufferedImage {
                val board = robotBoard()
                board.setSize(width, height)
                return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
                    val graphics = image.createGraphics()
                    try { board.paint(graphics) } finally { graphics.dispose() }
                }
            }
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertFalse(button("Connect", marsBot).isEnabled)
                    assertFalse(button("Reset", marsBot).isEnabled)
                    assertEquals("Select a MIPS execution to see its robot.", robotBoard().accessibleContext.accessibleDescription)
                    assertFalse(robotClock().isEnabled)
                }
            }
            withContext(Dispatchers.EDT) {
                marsBot.setSize(JBUI.scale(330), JBUI.scale(460))
                marsBot.doLayout()
                val setRate = button("Set rate", marsBot)
                val bounds = SwingUtilities.convertRectangle(setRate.parent, setRate.bounds, marsBot)
                assertTrue(bounds.width > 0 && bounds.x + bounds.width <= marsBot.width)
                robotClock().postActionEvent()
                paintRobot(1, 1)
                paintRobot(280, 180)
            }
            val robotRun = launch(run, 7, """
                li ${'$'}v0, 12
                syscall
                lui ${'$'}t0, 0xffff
                ori ${'$'}t0, ${'$'}t0, 0x8000
                li ${'$'}t1, 90
                sw ${'$'}t1, 0x10(${'$'}t0)
                li ${'$'}t1, 1
                sw ${'$'}t1, 0x20(${'$'}t0)
                sw ${'$'}t1, 0x50(${'$'}t0)
                li ${'$'}v0, 12
                syscall
                li ${'$'}t1, 128
                poll:
                lw ${'$'}t2, 0x30(${'$'}t0)
                blt ${'$'}t2, ${'$'}t1, poll
                sw ${'$'}zero, 0x50(${'$'}t0)
                li ${'$'}v0, 12
                syscall
            """.trimIndent())
            val robotProcess = robotRun.getUserData(RunContentDescriptor.DESCRIPTOR_KEY)!!.processHandler as MarsProcessHandler
            val robot = robotProcess.marsBot
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) { assertTrue(button("Connect", marsBot).isEnabled) }
            }
            val beforeTrail = withContext(Dispatchers.EDT) {
                assertTrue(robotLabels().any { it.text == "MMIO starts at 0xffff8010 (hover for port addresses)" &&
                    it.toolTipText == "Heading 0xffff8010, pen 0xffff8020, X 0xffff8030, Y 0xffff8040, move 0xffff8050" })
                button("Connect", marsBot).doClick()
                assertTrue(robot.snapshot().connected)
                assertEquals("64", robotClock().text)
                robotClock().text = "bad"
                robotClock().postActionEvent()
                robotClock().text = "0"
                robotClock().postActionEvent()
                robotClock().text = "1000001"
                button("Set rate", marsBot).doClick()
                assertEquals(64, robot.snapshot().instructionsPerTick)
                robotClock().text = "1"
                robotClock().postActionEvent()
                assertEquals(1, robot.snapshot().instructionsPerTick)
                paintRobot(280, 180)
            }
            robotProcess.processInput.write("x\n".toByteArray())
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertTrue(robotBoard().accessibleContext.accessibleDescription.endsWith("Pen down   Moving"))
                }
            }
            robotProcess.processInput.write("x\n".toByteArray())
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    val position = robot.snapshot().world.position
                    assertTrue(position.x >= 128)
                    assertEquals("Position (${position.x.toInt()}, 0)   Heading 90°   Pen down   Stopped",
                        robotBoard().accessibleContext.accessibleDescription)
                }
            }
            withContext(Dispatchers.EDT) {
                val image = paintRobot(280, 180)
                val pad = JBUI.scale(8)
                val left = (image.width - image.height) / 2 + pad
                val trailX = left + (image.height - 2 * pad) * 64 / 511
                assertTrue(beforeTrail.getRGB(trailX, pad) != image.getRGB(trailX, pad))
                button("Reset", marsBot).doClick()
                assertEquals(beforeTrail.getRGB(trailX, pad), paintRobot(280, 180).getRGB(trailX, pad))
                button("Disconnect", marsBot).doClick()
                assertFalse(robot.snapshot().connected)
                button("Connect", marsBot).doClick()
                val content = window.contentManager.contents.single { it.component === marsBot }
                assertTrue(window.contentManager.removeContent(content, true))
                assertFalse(robot.snapshot().connected)
                robotProcess.destroyProcess()
                assertTrue(checkNotNull(robotRun.manager).removeContent(robotRun, true))
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
                        container.remove(references)
                        container.remove(xray)
                        container.remove(cache)
                        container.remove(floating)
                        container.remove(lab)
                        container.remove(panels[9])
                        container.remove(panels[10])
                        container.remove(panels[11])
                        container.remove(panels[12])
                    }
                }
            }
        }
    }
}
