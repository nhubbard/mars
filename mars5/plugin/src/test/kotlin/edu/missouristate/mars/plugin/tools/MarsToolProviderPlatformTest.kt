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
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.pollAssertionsAsync
import com.intellij.ui.ComponentUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.withForcedRespectIsShowingClientProperty
import com.intellij.util.ui.withShowingChanged
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunService
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsToolProviderPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()

    @Test
    fun `native content loads on selection and dynamic provider removal disposes it`() = timeoutRunBlocking<Unit> {
        val loaded = mutableListOf<String>()
        val disposed = mutableListOf<String>()
        var cancel = true
        fun provider(id: String, title: String, create: () -> Unit = {}) = object : MarsToolProvider {
            override val id = id
            override val title = title
            override fun createContent(context: MarsToolContext): JComponent {
                assertSame(project, context.project)
                loaded += title
                Disposer.register(context.disposable) { disposed += title }
                create()
                return JBLabel(title)
            }
        }
        val temporary = Disposer.newDisposable()
        val parent = Disposer.newDisposable()
        val alpha = provider("example.alpha", "Alpha")
        val beta = provider("example.beta", "Beta") { error("Fixture failure") }
        val retry = provider("example.retry", "Retry") {
            if (cancel) throw CancellationException("Fixture cancellation")
        }
        val duplicates = listOf(provider("example.alpha", "Duplicate"), provider("", "Blank"))
        val manager = withContext(Dispatchers.EDT) {
            ToolWindowManager.getInstance(project).registerToolWindow(RegisterToolWindowTask("Test MARS tools")).contentManager
        }
        try {
            withContext(Dispatchers.EDT) {
                val base = ContentFactory.getInstance().createContent(JPanel(), "Base", false)
                manager.addContent(base)
                MarsToolExtensions(project, manager, parent)
                assertEquals(listOf("Base", "Introduction to Tools"), manager.contents.map { it.displayName })
            }
            // Simulate a plug-in change originating off the EDT.
            ExtensionTestUtil.addExtensions(MarsToolProvider.EP, listOf(alpha, beta, retry) + duplicates, temporary)
            pollAssertionsAsync(5.seconds, 30.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertEquals(listOf("Base", "Introduction to Tools", "Alpha", "Beta", "Retry"),
                        manager.contents.map { it.displayName })
                }
            }
            withContext(Dispatchers.EDT) {
                assertTrue(loaded.isEmpty())
                fun select(title: String) {
                    manager.setSelectedContent(manager.contents.single { it.displayName == title })
                }
                select("Alpha")
                select("Base")
                select("Alpha")
                assertEquals(listOf("Alpha"), loaded)
                select("Beta")
                assertEquals("Could not open Beta.", (manager.selectedContent!!.component as JBLabel).text)
                assertEquals(listOf("Beta"), disposed)
                select("Retry")
                assertEquals(listOf("Beta", "Retry"), disposed)
                cancel = false
                select("Base")
                select("Retry")
                assertEquals("Retry", (manager.selectedContent!!.component as JBLabel).text)
                Disposer.dispose(temporary)
                assertEquals(listOf("Base", "Introduction to Tools"), manager.contents.map { it.displayName })
                assertEquals(listOf("Alpha", "Beta", "Retry", "Retry"), loaded)
                assertEquals(listOf("Alpha", "Beta", "Retry", "Retry"), disposed.sorted())
            }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) {
                Disposer.dispose(temporary)
                Disposer.dispose(parent)
                manager.removeAllContents(true)
            }
        }
    }

    @Test
    fun `registered intro example receives instructions from only the selected execution`() = timeoutRunBlocking<Unit>(20.seconds) {
        val container = JPanel()
        val tool = withContext(Dispatchers.EDT) {
            val window = ToolWindowManager.getInstance(project).registerToolWindow(RegisterToolWindowTask("Example MARS tools"))
            val manager = window.contentManager
            MarsToolExtensions(project, manager, window.disposable)
            manager.contents.single().also { tab ->
                manager.setSelectedContent(tab)
                ComponentUtil.forceMarkAsShowing(container, true)
                withForcedRespectIsShowingClientProperty {
                    withShowingChanged { container.add(tab.component) }
                    UIUtil.dispatchAllInvocationEvents()
                }
            }
        }
        val firstReady = CompletableDeferred<Unit>()
        val secondReady = CompletableDeferred<Unit>()
        val first = withContext(Dispatchers.EDT) { execution(1, firstReady) }
        val second = withContext(Dispatchers.EDT) { execution(2, secondReady) }
        fun selector() = UIUtil.findComponentsOfType(tool.component, JComboBox::class.java).single()
        fun steps() = UIUtil.findComponentsOfType(tool.component, JBLabel::class.java)
            .single { it.text.startsWith("Observed instructions") }.text
        suspend fun checkUi(assert: () -> Unit) = pollAssertionsAsync(5.seconds, 30.milliseconds) {
            withContext(Dispatchers.EDT) { assert() }
        }
        try {
            firstReady.await()
            secondReady.await()
            checkUi {
                assertEquals(2, selector().itemCount)
                assertEquals("Observed instructions: 0", steps())
                val guide = UIUtil.findComponentsOfType(tool.component, JBTextArea::class.java).single()
                assertFalse(guide.isEditable)
                assertTrue(guide.text.contains("MarsToolProvider"))
                selector().selectedIndex = 1
            }
            first.first.processInput.write("x\n".toByteArray())
            checkUi { assertEquals("Observed instructions: 4", steps()) }
            withContext(Dispatchers.EDT) { selector().selectedIndex = 0 }
            checkUi { assertEquals("Observed instructions: 0", steps()) }
            first.first.processInput.write("x\n".toByteArray())
            second.first.processInput.write("x\n".toByteArray())
            checkUi { assertEquals("Observed instructions: 4", steps()) }
            withContext(Dispatchers.EDT) {
                first.second.manager!!.removeContent(first.second, true)
                second.second.manager!!.removeContent(second.second, true)
            }
            checkUi {
                assertEquals(0, selector().itemCount)
                assertEquals("Observed instructions: 0", steps())
            }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) {
                first.first.destroyProcess()
                second.first.destroyProcess()
                first.second.manager?.removeContent(first.second, true)
                second.second.manager?.removeContent(second.second, true)
                tool.manager!!.removeContent(tool, true)
                withForcedRespectIsShowingClientProperty { withShowingChanged { container.removeAll() } }
            }
        }
    }

    private fun execution(id: Long, ready: CompletableDeferred<Unit>): Pair<MarsProcessHandler, Content> {
        val file = fixture.addFileToProject("intro-$id.asm", """
            li ${'$'}v0, 12
            syscall
            nop
            nop
            li ${'$'}v0, 12
            syscall
            nop
            nop
            nop
            nop
        """.trimIndent()).virtualFile
        val process = project.service<MarsRunService>().createProcess(file.toNioPath(), memoryDumps = emptyList()) { session, _ ->
            val simulator = session.simulator()
            runSlice(simulator, 1)
            ready.complete(Unit)
            runSlice(simulator)
        }
        val descriptor = RunContentDescriptor(null, process, JPanel(), "Example $id").apply {
            executionId = id
            isActivateToolWindowWhenAdded = false
        }
        val manager = ToolWindowManager.getInstance(project)
        val toolId = DefaultRunExecutor.getRunExecutorInstance().toolWindowId
        val window = manager.getToolWindow(toolId) ?: manager.registerToolWindow(RegisterToolWindowTask(toolId))
        val content = ContentFactory.getInstance().createContent(descriptor.component, descriptor.displayName, false)
        content.putUserData(RunContentDescriptor.DESCRIPTOR_KEY, descriptor)
        content.setDisposer(descriptor)
        window.contentManager.addContent(content)
        process.startNotify()
        return process to content
    }
}
