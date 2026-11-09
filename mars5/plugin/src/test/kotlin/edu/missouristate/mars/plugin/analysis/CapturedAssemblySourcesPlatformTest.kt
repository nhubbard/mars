package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class CapturedAssemblySourcesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `captures uncommitted entry and transitive include documents before assembly`() = timeoutRunBlocking<Unit> {
        val mainText = ".include \"./parts/outer.txt\"\nli \$t0, VALUE\n"
        val innerText = ".eqv VALUE 42\n"
        val (main, outer, inner, handler) = withContext(Dispatchers.EDT) {
            val leaf = fixture.addFileToProject("parts/with spaces#é.txt", ".eqv VALUE 1\n").virtualFile
            val entry = fixture.addFileToProject("main.asm", "nop\n").virtualFile
            val intermediate = fixture.addFileToProject("parts/outer.txt", ".include \"${leaf.path}\"\n").virtualFile
            val start = fixture.addFileToProject("startup.s", "addiu \$s0, \$zero, 17\n").virtualFile
            edit(entry, mainText)
            edit(leaf, innerText)
            listOf(entry, intermediate, leaf, start)
        }
        val entry = main.toNioPath()
        val leaf = inner.toNioPath()
        val handlerPath = handler.toNioPath()
        val capture = captureAssemblySources(listOf(entry), handlerPath)
        assertEquals(listOf(entry), capture.snapshot.entries())
        assertEquals(handlerPath, capture.exceptionHandler)
        assertEquals(setOf(entry, outer.toNioPath(), leaf, handlerPath), capture.snapshot.texts().keys)
        assertEquals(capture.snapshot.texts().keys, capture.revisions.keys)
        assertEquals(mainText, capture.snapshot.texts()[entry])
        assertEquals(innerText, capture.snapshot.texts()[leaf])
        assertEquals("nop\n", Files.readString(entry))
        assertEquals(".eqv VALUE 1\n", Files.readString(leaf))

        withContext(Dispatchers.EDT) { edit(inner, ".eqv VALUE 99\n") }
        val newer = captureAssemblySources(listOf(entry), handlerPath)
        assertEquals(".eqv VALUE 99\n", newer.snapshot.texts()[leaf])
        assertEquals(innerText, capture.snapshot.texts()[leaf])
        assertFalse(capture.revisions[leaf] == newer.revisions[leaf])

        val context = project.get().getService(MarsSessionService::class.java).context
        val program = context.assembleSnapshot(capture.snapshot, capture.exceptionHandler, true, false)
        assertEquals(2, program.instructionCount())
        assertTrue(program.simulate(5))
        assertEquals(42, context.registerFile.getValue(8))
        assertEquals(17, context.registerFile.getValue(16))
    }

    @Test
    fun `captures only reachable files and leaves include failures for assembler diagnostics`() = timeoutRunBlocking<Unit> {
        val main = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("ignored.txt", "nop\n")
            fixture.addFileToProject("parts/filler.txt", "nop\n")
            fixture.addFileToProject("cycle.s", ".include \"./main.asm\"\n")
            val entry = fixture.addFileToProject("main.asm", "nop\n").virtualFile
            edit(entry, """
                # .include "ignored.txt"
                .include "absent.txt"
                .include "parts"
                .include "invalid${'\u0000'}name"
                .include "cycle.s"
            """.trimIndent() + "\n")
            entry.toNioPath()
        }
        val capture = captureAssemblySources(listOf(main), null)
        assertEquals(setOf(main, main.resolveSibling("cycle.s")), capture.snapshot.texts().keys)
        assertEquals(capture.snapshot.texts().keys, capture.revisions.keys)
    }

    private fun edit(file: VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project.get()) { document.setText(text) }
    }
}
