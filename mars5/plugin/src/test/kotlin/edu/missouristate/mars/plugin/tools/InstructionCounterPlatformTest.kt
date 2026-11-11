package edu.missouristate.mars.plugin.tools

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
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
import com.intellij.ui.dsl.builder.panel
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
import edu.missouristate.mars.plugin.tools.xray.XRayUnit
import edu.missouristate.mars.plugin.tools.xray.XRayView
import edu.missouristate.mars.plugin.tools.xray.xRayLabel
import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

@TestApplication
class InstructionCounterPlatformTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun initializeSkia() {
            // The native reference cleaner is shared for the lifetime of the test JVM.
            Surface.makeRasterN32Premul(1, 1).close()
        }
    }
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)

    @Test
    fun `tool execution selectors keep their labels above them and fit narrow docks`() = timeoutRunBlocking<Unit> {
        val project = projectFixture.get()
        withContext(Dispatchers.EDT) {
            val selector = MarsToolExecutionSelector(project) {}.component
            val body = panel { marsExecutionRow(selector) }
            val label = UIUtil.findComponentsOfType(body, JLabel::class.java).single { it.labelFor === selector }
            for (width in listOf(180, 640)) {
                body.setSize(JBUI.size(width, 120))
                body.doLayout()
                assertTrue(label.y + label.height <= selector.y)
                assertTrue(selector.x + selector.width <= body.width)
                assertTrue(selector.width in JBUI.scale(112)..JBUI.scale(240))
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalJewelApi::class)
    @Test
    fun `MARS Tools selects native executions and controls their counters independently`() = timeoutRunBlocking<Unit>(45.seconds) {
        val project = projectFixture.get()
        val manager = ToolWindowManager.getInstance(project)
        val run = DefaultRunExecutor.getRunExecutorInstance()
        val debug = DefaultDebugExecutor.getDebugExecutorInstance()
        val container = JPanel()
        val window = withContext(Dispatchers.EDT) { manager.registerToolWindow(RegisterToolWindowTask("MARS Tools")) }
        val (panels, keyboardEditor) = withContext(Dispatchers.EDT) {
            val editorFactory = EditorFactory.getInstance()
            val existingEditors = editorFactory.allEditors.toSet()
            MarsToolsWindowFactory().createToolWindowContent(project, window)
            val toolContents = window.contentManager
            val contents = toolContents.contents
            assertEquals(listOf("Instruction Counter", "Instruction Statistics", "Branch History Table", "Bitmap Display",
                "Memory Reference Visualization", "MIPS X-Ray", "Data Cache Simulator", "Floating Point Representation",
                "Digital Lab Sim", "Keyboard and Display Simulator", "MarsBot", "Scavenger Hunt", "Introduction to Tools"),
                contents.map { it.displayName })
            assertSame(contents[0], toolContents.selectedContent)
            assertTrue(contents.drop(1).all { it.component is JBLabel })
            assertEquals(existingEditors, editorFactory.allEditors.toSet())
            val terminal = contents[9]
            toolContents.setSelectedContent(terminal)
            val terminalPanel = terminal.component
            val editor = (editorFactory.allEditors.toSet() - existingEditors).single()
            assertTrue(SwingUtilities.isDescendingFrom(editor.component, terminalPanel))
            assertTrue(terminal.preferredFocusableComponent is JBTextField)
            toolContents.setSelectedContent(contents[0])
            toolContents.setSelectedContent(terminal)
            assertSame(terminalPanel, terminal.component)
            assertEquals(existingEditors + editor, editorFactory.allEditors.toSet())
            contents.forEach { toolContents.setSelectedContent(it) }
            toolContents.setSelectedContent(contents[0])
            ComponentUtil.forceMarkAsShowing(container, true)
            withForcedRespectIsShowingClientProperty {
                UIUtil.dispatchAllInvocationEvents()
                withShowingChanged {
                    contents.forEach { container.add(it.component) }
                }
                UIUtil.dispatchAllInvocationEvents()
            }
            contents.map { it.component } to editor
        }
        val (panel, statistics, branches, bitmap, references) = panels
        val xray = withContext(Dispatchers.EDT) {
            assertEquals("MIPS X-Ray", panels[5].accessibleContext.accessibleName)
            XRayView(project).also { view ->
                withForcedRespectIsShowingClientProperty {
                    UIUtil.dispatchAllInvocationEvents()
                    withShowingChanged { container.add(view.component) }
                    UIUtil.dispatchAllInvocationEvents()
                }
            }
        }
        val xrayScene = withContext(Dispatchers.EDT) {
            ImageComposeScene(320, 880).apply { setContent { SwingBridgeTheme(content = xray.content) } }
        }
        var xrayTime = 0L
        fun renderXRay(): List<SemanticsNode> {
            xrayScene.render(xrayTime.also { xrayTime += 16_000_000 }).close()
            return xrayScene.semanticsOwners.flatMap { it.rootSemanticsNode.flatten() }
        }
        fun xrayText() = renderXRay().map { it.labelText() }
        fun clickXRay(label: String) {
            val node = renderXRay().first {
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    label in (it.flatten().map { child -> child.labelText() } +
                        it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
            }
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }
        fun chooseXRay(label: String, value: String) {
            fun tap(point: Offset) {
                xrayScene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
                xrayScene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
            }
            val control = renderXRay().first { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            tap(control.boundsInWindow.center)
            val option = renderXRay().last { it.labelText() == value }
            tap(option.boundsInWindow.center)
            renderXRay()
        }
        val cache = panels[6]
        val floating = panels[7]
        val lab = panels[8]
        val marsBot = panels[10]
        val (floatSelector, floatRegister) = withContext(Dispatchers.EDT) {
            UIUtil.findComponentsOfType(floating, JComboBox::class.java).sortedBy { it.itemCount }
        }
        fun floatField(columns: Int) = UIUtil.findComponentsOfType(floating, JBTextField::class.java).single { it.columns == columns }
        fun floatLabels() = UIUtil.findComponentsOfType(floating, JBLabel::class.java).map { it.text } +
            UIUtil.findComponentsOfType(floating, JEditorPane::class.java).map { it.document.getText(0, it.document.length).trim() }
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
            fun canvas(panel: JComponent) = UIUtil.findComponentsOfType(panel, MemoryGridCanvas::class.java).single()
            fun gridKey(key: Int, panel: JComponent = references) {
                val grid = canvas(panel)
                grid.getActionForKeyStroke(KeyStroke.getKeyStroke(key, 0)).actionPerformed(ActionEvent(grid, 0, ""))
            }
            fun focusGrid(panel: JComponent = references) {
                val grid = canvas(panel)
                val event = FocusEvent(grid, FocusEvent.FOCUS_GAINED)
                grid.focusListeners.forEach { it.focusGained(event) }
            }
            fun assertInspection(text: String, panel: JComponent = references) {
                assertEquals(text, canvas(panel).accessibleContext.accessibleDescription)
                assertTrue(UIUtil.findComponentsOfType(panel, JBLabel::class.java).any { it.text == text })
            }
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
            fun layoutDock(panel: JComponent, width: Int, height: Int): JScrollPane {
                val scroll = assertInstanceOf(JScrollPane::class.java, panel)
                scroll.setSize(JBUI.size(width, height))
                scroll.doLayout()
                scroll.viewport.doLayout()
                scroll.viewport.view.doLayout()
                return scroll
            }
            withContext(Dispatchers.EDT) {
                assertTrue("No MIPS executions" in xrayText())
                assertTrue("No captured instructions" in xrayText())
                assertFalse(button("Connect").isEnabled)
                assertFalse(button("Reset").isEnabled)
                assertFalse(button("Apply and Reset", branches).isEnabled)
                assertFalse(button("Apply and Clear", bitmap).isEnabled)
                assertFalse(button("Apply and Reset", cache).isEnabled)
                assertEquals(0, UIUtil.findComponentsOfType(cache, JBTable::class.java).single().rowCount)
                val memoryDock = layoutDock(references, 280, 460)
                val gridChoice = UIUtil.findComponentsOfType(references, JBCheckBox::class.java).single()
                val gridBounds = SwingUtilities.convertRectangle(gridChoice.parent, gridChoice.bounds, memoryDock.viewport.view)
                assertTrue(gridBounds.maxX <= memoryDock.viewport.viewRect.maxX)
                assertEquals(gridChoice.preferredSize.width, gridChoice.width)
                val threshold = UIUtil.findComponentsOfType(references, JSpinner::class.java).single()
                val thresholdLabel = UIUtil.findComponentsOfType(references, JLabel::class.java).single { it.labelFor === threshold }
                assertTrue(thresholdLabel.y + thresholdLabel.height <= threshold.y)

                for (panel in listOf(branches, cache)) {
                    val scroll = layoutDock(panel, 280, 200)
                    val viewport = scroll.viewport
                    val body = viewport.view
                    val table = UIUtil.findComponentsOfType(panel, JBTable::class.java).single()
                    assertEquals(JTable.AUTO_RESIZE_OFF, table.autoResizeMode)
                    val inner = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, table)
                    val tableBounds = SwingUtilities.convertRectangle(inner.parent, inner.bounds, body)
                    assertTrue(tableBounds.height >= JBUI.scale(84))
                    assertTrue(tableBounds.maxY > viewport.viewRect.maxY)
                    table.focusListeners.forEach { it.focusGained(FocusEvent(table, FocusEvent.FOCUS_GAINED)) }
                    assertTrue(viewport.viewRect.y <= tableBounds.y)
                    assertTrue(viewport.viewRect.maxY >= tableBounds.maxY)

                    val apply = button("Apply and Reset", panel)
                    val applyBounds = SwingUtilities.convertRectangle(apply.parent, apply.bounds, body)
                    assertTrue(applyBounds.maxX <= viewport.viewRect.maxX)
                    apply.focusListeners.forEach { it.focusGained(FocusEvent(apply, FocusEvent.FOCUS_GAINED)) }
                    assertTrue(viewport.viewRect.y <= applyBounds.y)
                    assertTrue(viewport.viewRect.maxY >= applyBounds.maxY)
                }
                assertEquals("Static data", baseLabel())
                for (panel in listOf(bitmap, references)) {
                    val scroll = assertInstanceOf(JScrollPane::class.java, panel)
                    scroll.setSize(JBUI.scale(240), JBUI.scale(208))
                    scroll.doLayout()
                    val viewport = scroll.viewport
                    viewport.doLayout()
                    val body = viewport.view
                    body.doLayout()
                    val grid = canvas(panel)
                    val gridBounds = SwingUtilities.convertRectangle(grid.parent, grid.bounds, body)
                    assertTrue(gridBounds.height >= JBUI.scale(128))
                    assertTrue(gridBounds.maxY > viewport.viewRect.maxY)

                    val reset = button("Reset", panel)
                    val resetBounds = SwingUtilities.convertRectangle(reset.parent, reset.bounds, body)
                    reset.focusListeners.forEach { it.focusGained(FocusEvent(reset, FocusEvent.FOCUS_GAINED)) }
                    assertTrue(viewport.viewRect.contains(resetBounds))

                    val details = UIUtil.findComponentsOfType(panel, JBLabel::class.java)
                        .single { it.text == grid.accessibleContext.accessibleDescription }
                    val detailsBounds = SwingUtilities.convertRectangle(details.parent, details.bounds, body)
                    grid.focusListeners.forEach { it.focusGained(FocusEvent(grid, FocusEvent.FOCUS_GAINED)) }
                    assertTrue(viewport.viewRect.y <= gridBounds.y)
                    assertTrue(viewport.viewRect.maxY >= detailsBounds.maxY)
                    grid.clearInspection()
                }
                assertEquals(null, tooltip(references, 10, 10))
                assertTrue(canvas(bitmap).isFocusable)
                assertNull(tooltip(bitmap, 1, 128))
                assertTrue(canvas(references).isFocusable)
                gridKey(KeyEvent.VK_RIGHT)
                assertInspection("This cell has no mapped address.")
                focusGrid()
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
                val floatScroll = assertInstanceOf(JScrollPane::class.java, floating)
                floatScroll.setSize(JBUI.scale(295), JBUI.scale(180))
                floatScroll.doLayout()
                val floatViewport = floatScroll.viewport
                floatViewport.doLayout()
                val floatBody = assertInstanceOf(JComponent::class.java, floatViewport.view)
                floatBody.doLayout()
                assertTrue(floatBody.width <= floatViewport.width) {
                    "Floating-point panel width ${floatBody.width} exceeds viewport width ${floatViewport.width}; " +
                        floatBody.components.joinToString { "${it.javaClass.simpleName}=${it.preferredSize.width}" }
                }
                val fractionField = floatField(23)
                fractionField.parent.doLayout()
                val fractionLabel = UIUtil.findComponentsOfType(floating, JLabel::class.java).single { it.labelFor === fractionField }
                val labelBounds = SwingUtilities.convertRectangle(fractionLabel.parent, fractionLabel.bounds, floatBody)
                val fieldBounds = SwingUtilities.convertRectangle(fractionField.parent, fractionField.bounds, floatBody)
                assertTrue(labelBounds.maxY <= fieldBounds.y)
                assertTrue(labelBounds.x >= 0 && labelBounds.maxX <= floatViewport.width)
                assertTrue(fieldBounds.x >= 0 && fieldBounds.maxX <= floatViewport.width)
                val fractionMetrics = fractionField.getFontMetrics(fractionField.font)
                assertTrue(fractionMetrics.stringWidth(fractionField.text) + fractionMetrics.charWidth('0') <=
                    fractionField.width - fractionField.insets.left - fractionField.insets.right)
                val floatReset = button("Reset", floating)
                val floatResetBounds = SwingUtilities.convertRectangle(floatReset.parent, floatReset.bounds, floatBody)
                assertTrue(floatResetBounds.height > 0)
                assertTrue(floatResetBounds.maxY > floatViewport.viewRect.maxY)
                floatReset.focusListeners.forEach { it.focusGained(FocusEvent(floatReset, FocusEvent.FOCUS_GAINED)) }
                assertTrue(floatViewport.viewRect.contains(floatResetBounds))
                floatReset.doClick()
                assertEquals("0x00000000", floatField(12).text)
                assertEquals(0, fractionField.caretPosition)
                assertEquals(0, fractionField.scrollOffset)
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
                    assertEquals(2, xray.executions.size)
                    xray.selectExecution(xray.executions[1])
                    chooseXRay("Execution", xray.executions[0].label)
                    assertEquals(2L, xray.step?.stepCount())
                    assertTrue("2 of 2" in xrayText())
                    clickXRay("Previous step")
                    assertEquals(1L, xray.step?.stepCount())
                    assertTrue("1 of 2" in xrayText())
                    clickXRay("Next step")
                    assertEquals(2L, xray.step?.stepCount())
                    chooseXRay("Instruction", xray.steps.first().xRayLabel())
                    assertEquals(1L, xray.step?.stepCount())
                    clickXRay("Next step")
                    clickXRay("Previous step")
                    chooseXRay("Diagram", "Registers")
                    assertEquals(XRayUnit.REGISTERS, xray.unit)
                    for (choice in XRayUnit.entries) {
                        xray.selectUnit(choice)
                        assertTrue(xray.canPlay)
                    }
                    chooseXRay("Playback speed", "2×")
                    assertEquals(XRayView.Rate.FAST, xray.rate)
                    clickXRay("Reset animation")
                    clickXRay("Play animation")
                    assertTrue(xray.playing)
                    clickXRay("Pause animation")
                    assertFalse(xray.playing)
                    clickXRay("Play animation")
                }
            }
            pollAssertionsAsync(8.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) { assertFalse(xray.playing) }
            }
            val trace = (second.getUserData(RunContentDescriptor.DESCRIPTOR_KEY)!!.processHandler as MarsProcessHandler).xray
            trace.record(StepEvent(0x00400008, -1, 3))
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertEquals(3, xray.steps.size)
                    assertEquals(1L, xray.step?.stepCount())
                    assertTrue(xray.steps.last().xRayLabel().contains("Unknown instruction"))
                }
            }
            trace.rewind(0)
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) { assertFalse(xray.canPlay) }
            }
            withContext(Dispatchers.EDT) {
                withForcedRespectIsShowingClientProperty {
                    withShowingChanged { container.remove(xray.component) }
                    UIUtil.dispatchAllInvocationEvents()
                    withShowingChanged { container.add(xray.component) }
                    UIUtil.dispatchAllInvocationEvents()
                }
            }
            trace.record(StepEvent(0x00400000, 0, 1))
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) { assertTrue(xray.canPlay) }
            }
            withContext(Dispatchers.EDT) {
                clickXRay("Disconnect")
                assertFalse(trace.connected)
                assertTrue(xray.canPlay)
                trace.record(StepEvent(0x00400004, 0, 2))
                assertEquals(1, trace.snapshot().size)

                xray.selectExecution(xray.executions[1])
                assertTrue(xray.connected)
                assertTrue(xray.step!!.xRayLabel().contains("addiu"))
                xray.selectExecution(xray.executions[0])
                assertFalse(xray.connected)
                assertEquals(1, xray.steps.size)
                clickXRay("Clear trace")
                assertFalse(xray.canPlay)
                assertFalse(trace.connected)
                clickXRay("Connect")
                assertTrue(trace.connected)
                trace.record(StepEvent(0x00400008, 0, 3))
            }
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertTrue(xray.canPlay)
                    assertEquals(3L, xray.step?.stepCount())
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
                    assertTrue("128 bytes   |   Accesses 2" in status, status.toString())
                    assertTrue("Hits 1   |   Misses 1" in status, status.toString())
                    assertTrue("50%" in status)
                    assertEquals(50, UIUtil.findComponentsOfType(cache, JProgressBar::class.java).single().value)
                }
            }
            withContext(Dispatchers.EDT) {
                val wideCacheDock = layoutDock(cache, 610, 460)
                val hitRate = UIUtil.findComponentsOfType(cache, JProgressBar::class.java).single()
                val percent = UIUtil.findComponentsOfType(cache, JBLabel::class.java).single { it.text == "50%" }
                assertTrue(percent.x + percent.width <= hitRate.x)
                assertTrue(hitRate.width in 1..wideCacheDock.viewport.extentSize.width / 2)
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
                assertEquals(null, tooltip(bitmap, 1, 127))
                assertEquals("(1, 0) · 0x10010004 · #0000ff", tooltip(bitmap, 1, 128))
                focusGrid(bitmap)
                assertInspection("(0, 0) · 0x10010000 · #ff0000", bitmap)
                gridKey(KeyEvent.VK_RIGHT, bitmap)
                assertInspection("(1, 0) · 0x10010004 · #0000ff", bitmap)
                val selectedPixel = renderBitmap()
                assertEquals(Color.BLUE.rgb, selectedPixel.getRGB(1, 128))
                assertEquals(Color.WHITE.rgb, selectedPixel.getRGB(1 + JBUI.scale(7) / 2, 128))
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
                focusGrid()
                assertInspection("0x10010000 · 1 accesses")
                gridKey(KeyEvent.VK_LEFT)
                gridKey(KeyEvent.VK_UP)
                assertInspection("0x10010000 · 1 accesses")
                gridKey(KeyEvent.VK_RIGHT)
                assertInspection("0x10010004 · 1 accesses")
                assertEquals(Color.WHITE.rgb, renderBitmap(references).getRGB(32, 10))
                UIUtil.findComponentsOfType(references, JComboBox::class.java).single { it.itemCount == 12 }.selectedItem = 2
                button("Apply and Clear", references).doClick()
                assertEquals("0x10010008 · 0 accesses", tooltip(references, 33, 10))
                assertInspection("0x10010008 · 0 accesses")
                gridKey(KeyEvent.VK_DOWN)
                assertInspection("0x10010088 · 0 accesses")
                val grid = canvas(references)
                fun clickReference(x: Int) = grid.dispatchEvent(MouseEvent(grid, MouseEvent.MOUSE_CLICKED, 0, 0, x, 10, 1, false))
                clickReference(-1)
                assertInspection("0x10010088 · 0 accesses")
                clickReference(10)
                assertInspection("0x10010000 · 0 accesses")
                baseChoice().selectedItem = Base.HEAP
                button("Disconnect", bitmap).doClick()
                button("Apply and Clear", bitmap).doClick()
                assertEquals(0xff000000.toInt(), renderBitmap().getRGB(0, 128))
                assertInspection("(1, 0) · 0x10040004 · #000000", bitmap)
                button("Connect", bitmap).doClick()
                button("Reset", bitmap).doClick()
                assertEquals(Base.HEAP, baseChoice().selectedItem)
                baseChoice().selectedItem = Base.MMIO
                button("Apply and Clear", bitmap).doClick()
                assertNull(tooltip(bitmap, 0, 160))
                assertTrue(checkNotNull(bitmapRun.manager).removeContent(bitmapRun, true))
            }
            waitFor(0, "0 (0%)")
            withContext(Dispatchers.EDT) {
                assertInspection("Focus or click to inspect a cell.")
            }

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
            val liveTablesRun = launch(run, 11, """
                .data
                value: .word 0
                .text
                la ${'$'}t0, value
                sw ${'$'}zero, 0(${'$'}t0)
                bne ${'$'}zero, ${'$'}zero, done
                li ${'$'}v0, 5
                syscall
                lw ${'$'}t1, 0(${'$'}t0)
                beq ${'$'}zero, ${'$'}zero, done
                done: nop
            """.trimIndent())
            val liveProcess = liveTablesRun.getUserData(RunContentDescriptor.DESCRIPTOR_KEY)!!.processHandler!!
            suspend fun checkTableUpdate(accesses: String, decisions: Int, checkSelection: (JBTable) -> Unit) {
                pollAssertionsAsync(5.seconds, 30.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val cacheTable = UIUtil.findComponentsOfType(cache, JBTable::class.java).single()
                        val branchTable = UIUtil.findComponentsOfType(branches, JBTable::class.java).single()
                        val branchLog = UIUtil.findComponentsOfType(branches, JBTextArea::class.java).single()
                        assertTrue(cacheTable.rowCount > 1 && branchTable.rowCount > 1)
                        assertEquals(accesses, cacheTable.getValueAt(0, 3))
                        assertEquals(decisions, branchLog.text.lineSequence().count { it.isNotBlank() })
                        checkSelection(cacheTable)
                        checkSelection(branchTable)
                    }
                }
            }
            checkTableUpdate("1", 1) { it.setRowSelectionInterval(1, 1) }
            liveProcess.processInput!!.write("0\n".toByteArray())
            checkTableUpdate("2", 2) { assertEquals(1, it.selectedRow) }
            withContext(Dispatchers.EDT) {
                for (panel in listOf(branches, cache)) {
                    button("Reset", panel).doClick()
                    assertEquals(-1, UIUtil.findComponentsOfType(panel, JBTable::class.java).single().selectedRow)
                }
                assertTrue(checkNotNull(liveTablesRun.manager).removeContent(liveTablesRun, true))
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
                    val labels = UIUtil.findComponentsOfType(lab, JBLabel::class.java).map { it.text }
                    assertTrue(labels.containsAll(listOf(
                        "Left display (0x06)", "Right display (0x3f)", "28 instructions until next interrupt",
                    )))
                }
            }
            withContext(Dispatchers.EDT) {
                val right = labPanels().single { it.toolTipText == "Right display (0x3f)" }
                right.size = right.preferredSize
                val image = BufferedImage(right.width, right.height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                try { right.paint(graphics) } finally { graphics.dispose() }
                val background = image.getRGB(JBUI.scale(20), JBUI.scale(26))
                assertNotEquals(background, image.getRGB(JBUI.scale(20), JBUI.scale(7)))
                assertNotEquals(background, image.getRGB(JBUI.scale(20), JBUI.scale(31)))
                assertEquals(background, image.getRGB(JBUI.scale(20), JBUI.scale(33)))
                assertEquals("Right display (0x3f)", right.accessibleContext.accessibleName)
                val key = UIUtil.findComponentsOfType(lab, JToggleButton::class.java).single { it.text == "A" }
                val scroll = assertInstanceOf(JScrollPane::class.java, lab)
                scroll.setSize(JBUI.scale(210), JBUI.scale(200))
                scroll.doLayout()
                val viewport = scroll.viewport
                viewport.doLayout()
                val body = viewport.view
                body.doLayout()
                key.parent.doLayout()
                val lastKey = UIUtil.findComponentsOfType(lab, JToggleButton::class.java).single { it.text == "F" }
                val bounds = SwingUtilities.convertRectangle(lastKey.parent, lastKey.bounds, body)
                assertTrue(bounds.width > 0 && bounds.height > 0)
                assertFalse(viewport.viewRect.contains(bounds))
                val focus = FocusEvent(lastKey, FocusEvent.FOCUS_GAINED)
                lastKey.focusListeners.forEach { it.focusGained(focus) }
                assertTrue(viewport.viewRect.contains(bounds))
                key.doClick()
                assertEquals(10, labProcess.digitalLab.snapshot().key)
                assertTrue(key.isSelected)
                button("Reset", lab).doClick()
                assertFalse(key.isSelected)
                assertEquals("Right display (0x00)", right.toolTipText)
                assertTrue(UIUtil.findComponentsOfType(lab, JBLabel::class.java).any { it.text == "Right display (0x00)" })
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
                    assertEquals("Select a MIPS execution.", robotBoard().accessibleContext.accessibleDescription)
                    assertFalse(robotClock().isEnabled)
                }
            }
            withContext(Dispatchers.EDT) {
                marsBot.setSize(JBUI.scale(330), JBUI.scale(460))
                marsBot.doLayout()
                val viewport = assertInstanceOf(JScrollPane::class.java, marsBot).viewport
                viewport.doLayout()
                viewport.view.doLayout()
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
                assertTrue(robotLabels().any { it.text == "MMIO base 0xffff8010" &&
                    it.toolTipText == "Heading 0xffff8010, pen 0xffff8020, X 0xffff8030, Y 0xffff8040, move 0xffff8050" })
                button("Connect", marsBot).doClick()
                assertTrue(robot.snapshot().connected)
                marsBot.setSize(JBUI.scale(295), JBUI.scale(175))
                marsBot.doLayout()
                val viewport = assertInstanceOf(JScrollPane::class.java, marsBot).viewport
                viewport.doLayout()
                assertTrue(viewport.view.width <= viewport.width) {
                    "Live MarsBot width ${viewport.view.width} exceeds viewport width ${viewport.width}"
                }
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
            withContext(NonCancellable + Dispatchers.EDT) {
                xrayScene.close()
                xray.dispose()
                window.contentManager.removeAllContents(true)
                assertTrue(keyboardEditor.isDisposed)
                withForcedRespectIsShowingClientProperty {
                    withShowingChanged {
                        container.remove(panel)
                        container.remove(statistics)
                        container.remove(branches)
                        container.remove(bitmap)
                        container.remove(references)
                        container.remove(xray.component)
                        container.remove(panels[5])
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

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }

    private fun SemanticsNode.labelText(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

}
