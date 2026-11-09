package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.include.FileIncludeInfo
import com.intellij.psi.impl.include.FileIncludeManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.IncorrectOperationException
import edu.missouristate.mars.plugin.language.index.MipsIncludeProvider
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsFileReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native caret references navigate include path segments and ignore other strings`() = timeoutRunBlocking<Unit> {
        val files = withContext(Dispatchers.EDT) {
            val target = fixture.addFileToProject("parts/target.asm", "target: nop\n")
            val source = fixture.addFileToProject("main.asm", ".include \"parts/target.asm\"\n.asciiz \"parts/target.asm\"\n.include \"\"\n")
            fixture.configureFromExistingVirtualFile(source.virtualFile)
            target to source
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.editor.caretModel.moveToOffset(files.second.text.indexOf("target.asm") + 2)
            val reference = fixture.getReferenceAtCaretPosition()
            assertNotNull(reference)
            assertEquals(files.first, reference!!.resolve())
            assertInstanceOf(MipsStringLiteral::class.java, reference.element)
            assertEquals("target.asm", reference.rangeInElement.substring(reference.element.text))
        }
        readAction {
            val strings = PsiTreeUtil.findChildrenOfType(files.second, MipsStringLiteral::class.java).toList()
            assertEquals(3, strings.size)
            assertEquals(files.first.containingDirectory, strings[0].reference!!.resolve())
            assertEquals(2, strings[0].references.size)
            assertTrue(strings[1].references.isEmpty())
            assertNull(strings[1].reference)
            assertTrue(strings[2].references.isEmpty())
            assertNull(strings[2].reference)
            assertEquals(listOf("parts", "target.asm"), strings[0].references.map { it.canonicalText })
        }
    }

    @Test
    fun `native file references preserve raw spaces question marks unicode and repeated separators`() = timeoutRunBlocking<Unit> {
        val files = withContext(Dispatchers.EDT) {
            val raw = fixture.addFileToProject(" target?é.asm ", "target: nop\n")
            val child = fixture.addFileToProject("parts/child.s", "child: nop\n")
            val source = fixture.addFileToProject(
                "main.asm", ".include \" target?é.asm \"\n.include \"./parts//child.s\"\n.include \"${child.virtualFile.path}\"\n",
            )
            Triple(raw, child, source)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val strings = PsiTreeUtil.findChildrenOfType(files.third, MipsStringLiteral::class.java).toList()
            assertEquals(files.first, strings[0].references.last().resolve())
            assertEquals(files.second, strings[1].references.last().resolve())
            assertEquals(files.second, strings[2].references.last().resolve()) {
                val set = MipsFileReferenceSet(strings[2].text.drop(1).dropLast(1), strings[2])
                "path=${set.pathString}, absolute=${set.isAbsolutePathReference}, contexts=${set.defaultContexts.map { it.virtualFile.path }}, " +
                    set.allReferences.joinToString { "${it.canonicalText} -> ${it.resolve()?.virtualFile?.path}" }
            }
            assertEquals(setOf(files.third.virtualFile), FileIncludeManager.getManager(project.get()).getIncludingFiles(files.first.virtualFile, true).toSet())
            for (literal in strings) {
                val path = literal.text.substring(1, literal.textLength - 1)
                val references = MipsFileReferenceSet(path, literal)
                assertEquals(path, references.pathString)
                assertEquals(path.startsWith('/'), references.isAbsolutePathReference)
            }
        }
    }

    @Test
    fun `native include navigation does not substitute a lexical parent when the symlink target has no VFS parent`(
        @TempDir outsideProject: Path,
    ) = timeoutRunBlocking<Unit> {
        val psi = withContext(Dispatchers.EDT) {
            val path = Files.writeString(outsideProject.resolve("main.asm"), ".include \"shortcut/../value.s\"\n")
            Files.writeString(outsideProject.resolve("value.s"), "nop\n")
            val link = Files.createSymbolicLink(outsideProject.resolve("shortcut"), outsideProject.root)
            val manager = VirtualFileManager.getInstance()
            checkNotNull(manager.refreshAndFindFileByNioPath(link))
            val source = checkNotNull(manager.refreshAndFindFileByNioPath(path))
            fixture.configureFromExistingVirtualFile(source)
            fixture.file
        }
        readAction {
            val literal = PsiTreeUtil.findChildOfType(psi, MipsStringLiteral::class.java)!!
            val references = literal.references
            assertNotNull(references.first().resolve())
            assertNull(references[1].resolve())
            assertNull(references.last().resolve())
        }
    }

    @Test
    fun `unresolved native reference resolves after target creation without source edits`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) { fixture.addFileToProject("main.asm", ".include \"later.asm\"\n") }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val reference = readAction {
            val literal = PsiTreeUtil.findChildOfType(source, MipsStringLiteral::class.java)!!
            literal.references.single().also { assertNull(it.resolve()) }
        }
        val target = withContext(Dispatchers.EDT) { fixture.addFileToProject("later.asm", "target: nop\n") }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction { assertEquals(target, reference.resolve()) }
    }

    @Test
    fun `platform filename completion supplies and inserts include path candidates`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/target.asm", "target: nop\n")
            fixture.addFileToProject("parts/tangent.s", "tangent: nop\n")
            fixture.configureByText("main.asm", ".include \"parts/ta<caret>\"\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.completeBasic()
            assertTrue(fixture.lookupElementStrings!!.containsAll(listOf("target.asm", "tangent.s")))
            val selected = fixture.lookup.currentItem!!.lookupString
            fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertEquals(".include \"parts/$selected\"\n", fixture.file.text)
        }
    }

    @Test
    fun `native rename updates include paths in statements macros and replacement text only`() = timeoutRunBlocking<Unit> {
        val files = withContext(Dispatchers.EDT) {
            val target = fixture.addFileToProject("parts/target.asm", "target: nop\n")
            val source = fixture.addFileToProject("main.asm", """
                .include "parts/target.asm"
                .macro m
                  .include "parts/target.asm"
                .end_macro
                .eqv replacement .include "parts/target.asm"
                .asciiz "parts/target.asm"
                # .include "parts/target.asm"
            """.trimIndent() + "\n")
            target to source
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            RenameProcessor(project.get(), files.first, "renamed.asm", false, false).run()
        }
        readAction {
            assertEquals(3, Regex("parts/renamed.asm").findAll(files.second.text).count())
            assertTrue(files.second.text.contains(".asciiz \"parts/target.asm\""))
            assertTrue(files.second.text.contains("# .include \"parts/target.asm\""))
            for (literal in PsiTreeUtil.findChildrenOfType(files.second, MipsStringLiteral::class.java)) {
                for (reference in literal.references) assertNotNull(reference.resolve())
            }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            RenameProcessor(project.get(), files.first.containingDirectory, "library", false, false).run()
        }
        readAction {
            assertEquals(3, Regex("library/renamed.asm").findAll(files.second.text).count())
            assertTrue(files.second.text.contains(".asciiz \"parts/target.asm\""))
            assertTrue(files.second.text.contains("# .include \"parts/target.asm\""))
        }
    }

    @Test
    fun `native move rewrites relative includes through the registered element manipulator`() = timeoutRunBlocking<Unit> {
        val files = withContext(Dispatchers.EDT) {
            val target = fixture.addFileToProject("parts/target.asm", "target: nop\n")
            val source = fixture.addFileToProject("src/main.asm", ".include \"../parts/target.asm\"\n.include \"${target.virtualFile.path}\"\n")
            val destination = fixture.addFileToProject("destination/keep.asm", "").containingDirectory
            Triple(target, source, destination)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            MoveFilesOrDirectoriesProcessor(project.get(), arrayOf(files.first), files.third, false, false, null, null).run()
        }
        readAction {
            assertEquals(".include \"../destination/target.asm\"\n.include \"${files.first.virtualFile.path}\"\n", files.second.text)
            for (literal in PsiTreeUtil.findChildrenOfType(files.second, MipsStringLiteral::class.java)) {
                assertEquals(files.first, literal.references.last().resolve())
            }
        }
    }

    @Test
    fun `registered string manipulator preserves literal content and rejects malformed replacements`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            for (bad in listOf("early\"quote.asm", "line\nbreak.asm")) {
                val file = fixture.configureByText(MipsFileType, ".include \"old.asm\"\n")
                val literal = PsiTreeUtil.findChildOfType(file, MipsStringLiteral::class.java)!!
                val manipulator = ElementManipulators.getManipulator(literal)!!
                assertEquals(TextRange(1, literal.textLength - 1), manipulator.getRangeInElement(literal))
                WriteCommandAction.runWriteCommandAction(project.get()) {
                    val failure = assertThrows<IncorrectOperationException> { manipulator.handleContentChange(literal, bad) }
                    assertEquals("The path cannot be represented as a complete MARS string literal.", failure.message)
                }
                assertEquals(".include \"old.asm\"\n", file.text)
            }
            val file = fixture.configureByText(MipsFileType, ".include \"old.asm\"\n")
            val literal = PsiTreeUtil.findChildOfType(file, MipsStringLiteral::class.java)!!
            WriteCommandAction.runWriteCommandAction(project.get()) {
                val updated = ElementManipulators.handleContentChange(literal, " raw?name.asm ")
                assertInstanceOf(MipsStringLiteral::class.java, updated)
            }
            assertEquals(".include \" raw?name.asm \"\n", file.text)
        }
    }

    @Test
    fun `native reference sets support empty and root path boundaries`() = timeoutRunBlocking<Unit> {
        readAction {
            for (path in listOf("", "/", "//")) {
                val file = PsiFileFactory.getInstance(project.get()).createFileFromText("root.asm", MipsFileType, ".include \"$path\"\n")
                val literal = PsiTreeUtil.findChildOfType(file, MipsStringLiteral::class.java)!!
                val references = MipsFileReferenceSet(path, literal)
                assertEquals(path, references.pathString)
                assertEquals(if (path.isEmpty()) 0 else 1, references.allReferences.size)
                for (reference in references.allReferences) assertEquals("/", reference.canonicalText)
                assertNull(MipsIncludeProvider().resolveIncludedFile(FileIncludeInfo(path), file))
                assertNull(MipsIncludeProvider().resolveIncludedFile(FileIncludeInfo(path, 0), file))
            }
        }
    }
}
