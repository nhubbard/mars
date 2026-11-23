package edu.missouristate.mars.plugin.build

import com.intellij.build.BuildViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.SkippedResult
import com.intellij.build.events.StartBuildEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.replaceService
import edu.missouristate.mars.plugin.MarsExecutionOptions
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsBuildEventThreadTest {
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)

    @Test
    fun `repeated native order builds cache hits and diagnostics deliver every event on EDT`() = timeoutRunBlocking {
        val project = projects.get()
        val lifetime = Disposer.newDisposable()
        val events = CopyOnWriteArrayList<Pair<BuildEvent, Boolean>>()
        try {
            // Headless BuildViewManager omits its UI/listeners; observe its public event boundary.
            project.replaceService(BuildViewManager::class.java, object : BuildViewManager(project) {
                override fun onEvent(buildId: Any, event: BuildEvent) {
                    events += event to ApplicationManager.getApplication().isDispatchThread
                }
            }, lifetime)
            val (good, bad) = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("good.asm", ".data\nword: .word 0x12345678\n.text\nnop\n").virtualFile.toNioPath() to
                    fixture.addFileToProject("bad.asm", "not_an_instruction\n").virtualFile.toNioPath()
            }
            withContext(Dispatchers.Default) {
                val builds = project.service<MarsBuildService>()
                repeat(3) {
                    for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                        assertNotNull(builds.prepare(MarsSourceSet(good), MarsExecutionOptions(), true, order))
                    }
                }
                assertNull(builds.prepare(MarsSourceSet(bad), MarsExecutionOptions(), true))
            }
            assertEquals(7, events.count { it.first is StartBuildEvent })
            assertEquals(7, events.count { it.first is FinishBuildEvent })
            assertTrue(events.any { it.first is FileMessageEvent })
            assertTrue(events.all { it.second }, "Native Build UI events must not mutate Swing models from a worker")
        } finally { withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) } }
    }

    @Test
    fun `cancellation during UI start still emits one skipped finish on EDT`() = timeoutRunBlocking {
        val project = projects.get()
        val lifetime = Disposer.newDisposable()
        val events = CopyOnWriteArrayList<Pair<BuildEvent, Boolean>>()
        try {
            val entry = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("cancel.asm", "nop\n").virtualFile.toNioPath()
            }
            val operation = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                project.service<MarsBuildService>().prepare(MarsSourceSet(entry), MarsExecutionOptions(), true)
            }
            project.replaceService(BuildViewManager::class.java, object : BuildViewManager(project) {
                override fun onEvent(buildId: Any, event: BuildEvent) {
                    events += event to ApplicationManager.getApplication().isDispatchThread
                    if (event is StartBuildEvent) operation.cancel()
                }
            }, lifetime)
            operation.start()
            operation.join()
            assertTrue(operation.isCancelled)
            val start = assertInstanceOf(StartBuildEvent::class.java, events[0].first)
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events[1].first)
            assertEquals(2, events.size)
            assertEquals(start.id, finish.id)
            assertInstanceOf(SkippedResult::class.java, finish.result)
            assertTrue(events.all { it.second })
        } finally { withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) } }
    }
}
