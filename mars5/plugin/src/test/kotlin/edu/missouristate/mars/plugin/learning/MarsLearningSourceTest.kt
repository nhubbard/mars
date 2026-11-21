package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.xdebugger.XDebuggerUtil
import edu.missouristate.mars.plugin.run.MarsRunService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsLearningSourceTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)

    @Test
    fun `source evidence requires a loaded image current capability and correct local source position`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val project = projectFixture.get()
            val file = fixture.addFileToProject("observed.asm", ".text\nnop\n").virtualFile
            val other = fixture.addFileToProject("other.asm", ".text\nnop\n").virtualFile
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            val lifetime = Disposer.newDisposable()
            val handler = project.service<MarsRunService>().createProcess(file.toNioPath(), memoryDumps = emptyList())
            val source = MarsLearningSource(project, lifetime, file.toNioPath(), document, MarsConsoleDebugCheckpoint.identity)
            try {
                val attempt = source.begin("source-boundary")
                assertTrue(source.current(attempt))
                assertFalse(runReadActionBlocking { source.matches(attempt, handler) })
                val positions = XDebuggerUtil.getInstance()
                assertTrue(source.matchesPosition(positions.createPosition(file, 0)))
                assertFalse(source.matchesPosition(null))
                assertFalse(source.matchesPosition(positions.createPosition(other, 0)))
                assertFalse(source.matchesPosition(positions.createPosition(LightVirtualFile("captured.asm", "nop"), 0)))
                WriteCommandAction.runWriteCommandAction(project) { document.setText(".text\nnop\nnop\n") }
                assertFalse(runReadActionBlocking { source.matches(attempt, handler) })
                assertFalse(source.current(attempt))
                assertFalse(source.finish(attempt, true))
            } finally {
                handler.destroyProcess()
                Disposer.dispose(lifetime)
            }
        }
    }
}
