package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.include.FileIncludeInfo
import com.intellij.psi.impl.include.FileIncludeManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.indexing.FileContentImpl
import edu.missouristate.mars.plugin.language.index.MipsIncludeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsIncludeProviderPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `include producer preserves the assembler prepass rules without resolving paths`() {
        val source = """
            # .include "comment.asm"
            .asciiz ".include \"string.asm\""
            before: .INCLUDE, "parts/first.asm" .include "second-on-line.asm"
            .macro m
              .include "macro-body.asm"
            .end_macro
            .eqv replacement .include "replacement.asm"
            .include # no path
            "next-line.asm"
            .include 1 "not-a-path.asm"
            .include "unterminated
            .word 1
            .include
            .include "raw\n.asm"
            .include ""
        """.trimIndent() + "\n.include \"at-eof.asm\""
        val provider = MipsIncludeProvider()
        val file = LightVirtualFile("includes.asm", MipsFileType, source)
        val content = FileContentImpl.createByText(file, source, null)
        val infos = provider.getIncludeInfos(content)
        assertEquals(listOf("parts/first.asm", "macro-body.asm", "replacement.asm", "raw\\n.asm", "", "at-eof.asm"), infos.map { it.path })
        assertEquals(infos.map { source.lastIndexOf("\"${it.path}\"") }, infos.map { it.offset })
        assertTrue(infos.none { it.runtimeOnly })
        assertEquals("first.asm", infos.first().fileName)
        assertTrue(provider.acceptFile(content))
        val other = LightVirtualFile("other.txt", PlainTextFileType.INSTANCE, source)
        assertFalse(provider.acceptFile(FileContentImpl.createByText(other, source, null)))
        val types = ArrayList<FileType>()
        provider.registerFileTypesUsedForIndexing { types.add(it) }
        assertEquals(listOf(MipsFileType), types)
        assertEquals("mars", provider.id)
        assertEquals(1, provider.version)
        val empty = LightVirtualFile("empty.asm", MipsFileType, "")
        assertTrue(provider.getIncludeInfos(FileContentImpl.createByText(empty, "", null)).isEmpty())
    }

    @Test
    fun `shared include index resolves both graph directions and terminates recursive traversal`() = timeoutRunBlocking<Unit> {
        val files = withContext(Dispatchers.EDT) {
            listOf(
                fixture.addFileToProject("main.asm", ".include \"parts/child.asm\"\n").virtualFile,
                fixture.addFileToProject("parts/child.asm", ".include \"../leaf.s\"\n").virtualFile,
                fixture.addFileToProject("leaf.s", ".include \"main.asm\"\n").virtualFile,
            )
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val manager = FileIncludeManager.getManager(project.get())
            assertEquals(setOf(files[1]), manager.getIncludedFiles(files[0], true).toSet())
            assertEquals(setOf(files[0]), manager.getIncludingFiles(files[1], true).toSet())
            assertEquals(setOf(files[1]), manager.getIncludingFiles(files[2], true).toSet())
            assertEquals(files.toSet(), manager.getIncludedFiles(files[0], true, true).toSet())
        }
    }

    @Test
    fun `native include resolution supports absolute paths spaces unicode and dot segments`() = timeoutRunBlocking<Unit> {
        val files = withContext(Dispatchers.EDT) {
            val target = fixture.addFileToProject("names/with spaces#é.s", "target: nop\n").virtualFile
            val source = fixture.addFileToProject(
                "main.asm", ".include \"${target.path}\"\n.include \"./names/with spaces#é.s\"\n",
            ).virtualFile
            source to target
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val manager = FileIncludeManager.getManager(project.get())
            assertEquals(setOf(files.second), manager.getIncludedFiles(files.first, true).toSet())
            assertEquals(setOf(files.first), manager.getIncludingFiles(files.second, true).toSet())
        }
    }

    @Test
    fun `native resolution and reverse index find newly created targets and follow unsaved edits`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("main.asm", ".include \"missing.asm\"\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            assertTrue(FileIncludeManager.getManager(project.get()).getIncludedFiles(source.virtualFile, true).isEmpty())
        }
        val target = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("missing.asm", "target: nop\n").virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val manager = FileIncludeManager.getManager(project.get())
            assertEquals(target, manager.resolveFileInclude(FileIncludeInfo("missing.asm"), source)?.virtualFile)
            assertEquals(setOf(source.virtualFile), manager.getIncludingFiles(target, true).toSet())
        }
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(source.virtualFile)
            WriteCommandAction.runWriteCommandAction(project.get()) { fixture.editor.document.setText("# removed include\n") }
            PsiDocumentManager.getInstance(project.get()).commitAllDocuments()
        }
        readAction {
            val manager = FileIncludeManager.getManager(project.get())
            assertTrue(manager.getIncludedFiles(source.virtualFile, true).isEmpty())
            assertTrue(manager.getIncludingFiles(target, true).isEmpty())
        }
    }
}
