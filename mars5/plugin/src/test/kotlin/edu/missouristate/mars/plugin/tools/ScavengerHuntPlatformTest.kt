package edu.missouristate.mars.plugin.tools

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
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
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.withForcedRespectIsShowingClientProperty
import com.intellij.util.ui.withShowingChanged
import edu.missouristate.mars.plugin.run.MarsRunService
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class ScavengerHuntPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)

    @Test
    fun `tool window shows live scores and prevents conflicting attachments`() = timeoutRunBlocking<Unit>(20.seconds) {
        val project = projectFixture.get()
        val hunt = withContext(Dispatchers.EDT) { ScavengerHuntView(project) }
        val bot = withContext(Dispatchers.EDT) { MarsBotView(project) }
        val container = JPanel()
        fun button(panel: JComponent, name: String) =
            UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == name }
        fun huntButton(name: String) = button(hunt.component, name)
        fun botButton(name: String) = button(bot.component, name)
        fun labels() = UIUtil.findComponentsOfType(hunt.component, JBLabel::class.java).map { it.text }
        fun board() = UIUtil.findComponentsOfType(hunt.component, ScavengerHuntBoard::class.java).single()
        fun table() = UIUtil.findComponentsOfType(hunt.component, JBTable::class.java).single()
        fun paint(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
            board().setSize(width, height)
            val graphics = image.createGraphics()
            try { board().paint(graphics) } finally { graphics.dispose() }
        }
        val (process, content, blank) = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("hunt-panel.asm", """
                li ${'$'}v0, 12
                syscall
                lui ${'$'}s0, 0xffff
                ori ${'$'}s0, ${'$'}s0, 0xe000
                lui ${'$'}s1, 0xffff
                ori ${'$'}s1, ${'$'}s1, 0x8000
                li ${'$'}t0, 1
                sw ${'$'}t0, 8(${'$'}s0)
                sw ${'$'}t0, 4(${'$'}s0)
                li ${'$'}v0, 12
                syscall
                li ${'$'}t0, 2
                sw ${'$'}t0, 0(${'$'}s0)
                sw ${'$'}zero, 4(${'$'}s0)
                li ${'$'}t0, 0x28aaff
                sw ${'$'}t0, 0x1c(${'$'}s1)
                li ${'$'}t0, 257
                sw ${'$'}t0, 8(${'$'}s1)
                li ${'$'}t0, 1
                sw ${'$'}t0, 0x10(${'$'}s1)
                sw ${'$'}t0, 0x410(${'$'}s1)
                li ${'$'}v0, 12
                syscall
                nop
            """.trimIndent()).virtualFile
            assertFalse(huntButton("Connect").isEnabled)
            assertFalse(huntButton("Reset").isEnabled)
            assertEquals(0, table().rowCount)
            paint(1, 1)
            val blank = paint(320, 240)
            ComponentUtil.forceMarkAsShowing(container, true)
            withForcedRespectIsShowingClientProperty {
                withShowingChanged {
                    container.add(hunt.component)
                    container.add(bot.component)
                }
                UIUtil.dispatchAllInvocationEvents()
            }
            val process = project.service<MarsRunService>().createProcess(file.toNioPath(), memoryDumps = emptyList())
            process.setScavengerHuntConnected(false)
            process.setMarsBotConnected(false)
            process.setMarsBotConnected(true)
            process.resetScavengerHunt()
            assertNull(process.scavengerHuntSnapshot())
            val descriptor = RunContentDescriptor(null, process, JPanel(), "Hunt sample").apply {
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
            Triple(process, content, blank)
        }
        suspend fun waitFor(predicate: () -> Unit) = pollAssertionsAsync(5.seconds, 30.milliseconds) {
            withContext(Dispatchers.EDT) { predicate() }
        }
        try {
            waitFor {
                assertTrue(huntButton("Connect").isEnabled)
                assertTrue(botButton("Connect").isEnabled)
            }
            withContext(Dispatchers.EDT) { botButton("Connect").doClick() }
            waitFor {
                assertFalse(huntButton("Connect").isEnabled)
                assertTrue(huntButton("Connect").toolTipText.contains("share memory"))
                process.setScavengerHuntConnected(true)
                assertFalse(process.scavengerHuntSnapshot()!!.connected)
                botButton("Disconnect").doClick()
            }
            waitFor { assertTrue(huntButton("Connect").isEnabled) }
            withContext(Dispatchers.EDT) {
                huntButton("Connect").doClick()
                assertTrue(huntButton("Reset").isEnabled)
            }
            waitFor {
                assertFalse(botButton("Connect").isEnabled)
                assertTrue(botButton("Connect").toolTipText.contains("share memory"))
            }
            process.processInput.write("x\n".toByteArray())
            waitFor {
                assertTrue("Administrator   Turns remaining 0" in labels())
                assertTrue("0xffffe004  Authentication failed" in labels())
                assertEquals(22, table().rowCount)
            }
            process.processInput.write("x\n".toByteArray())
            waitFor {
                assertTrue("Player 0   Turns remaining 0" in labels())
                assertTrue("0xffff8410  Write outside the active player region" in labels())
                val table = table()
                assertEquals(listOf(0, "(257, 255)", 19, "0/7", null),
                    (0 until table.columnCount).map { table.getValueAt(0, it) })
            }
            withContext(Dispatchers.EDT) {
                val scoreboard = table()
                assertEquals("Finish", scoreboard.getColumnName(4))
                scoreboard.rowSorter.toggleSortOrder(2)
                assertEquals(0, scoreboard.getValueAt(0, 0))
                scoreboard.rowSorter.toggleSortOrder(2)
                assertEquals(0, scoreboard.getValueAt(scoreboard.rowCount - 1, 0))
                val image = paint(320, 240)
                val left = (image.width - ((image.height - 2 * JBUI.scale(10)) * 712.0 / 652).toInt()) / 2
                assertTrue((left + 20 until image.width - 40 step 2).any { x ->
                    (30 until image.height - 25 step 2).any { y -> image.getRGB(x, y) != blank.getRGB(x, y) }
                })
                huntButton("Reset").doClick()
                assertFalse(huntButton("Reset").isEnabled)
                assertTrue("Reset queued for the next instruction boundary." in labels())
                huntButton("Disconnect").doClick()
                assertFalse(process.scavengerHuntSnapshot()!!.resetPending)
                huntButton("Connect").doClick()
                assertTrue(process.scavengerHuntSnapshot()!!.connected)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) {
                Disposer.dispose(hunt)
                assertFalse(process.scavengerHuntSnapshot()!!.connected)
                Disposer.dispose(bot)
                process.destroyProcess()
                content.manager!!.removeContent(content, true)
                withForcedRespectIsShowingClientProperty { withShowingChanged { container.removeAll() } }
            }
        }
    }
}
