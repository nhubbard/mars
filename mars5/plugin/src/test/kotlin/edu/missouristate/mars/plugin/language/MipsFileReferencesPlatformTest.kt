package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
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
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.plugin.language.index.MipsIncludeProvider
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.assembleIn
import edu.missouristate.mars.plugin.analysis.captureAssemblySources
import edu.missouristate.mars.plugin.analysis.inferredMipsAssemblyEntry
import edu.missouristate.mars.plugin.analysis.mipsSourceTimeline
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet
import edu.missouristate.mars.plugin.language.references.resolveMipsDotSegment
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
    fun `include paths hidden by a malformed character recover references and update highlighting after edits`() =
        timeoutRunBlocking<Unit> {
            val source = "'a#xx .include,, \"part.s\"\nli \$t0, COUNT\n"
            val target = withContext(Dispatchers.EDT) {
                val included = fixture.addFileToProject("part.s", ".eqv COUNT 42\n")
                val entry = fixture.addFileToProject("main.asm", source)
                fixture.configureFromExistingVirtualFile(entry.virtualFile)
                included
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
            withContext(Dispatchers.EDT) {
                val editor = fixture.editor as EditorEx
                val directive = source.indexOf(".include")
                val path = source.indexOf("part.s")
                val closingQuote = source.lastIndexOf('"')
                fun tokenType(offset: Int) = editor.highlighter.createIterator(offset).tokenType
                fun assertReferences() {
                    assertEquals(MipsTypes.psi("INCLUDE"), tokenType(directive))
                    assertEquals(MipsTypes.psi("STRING"), tokenType(path))
                    editor.caretModel.moveToOffset(path + 1)
                    assertEquals(target, fixture.getReferenceAtCaretPosition()?.resolve())
                    editor.caretModel.moveToOffset(editor.document.text.lastIndexOf("COUNT") + 1)
                    assertEquals(target, fixture.getReferenceAtCaretPosition()?.resolve()?.containingFile)
                }
                assertReferences()
                WriteCommandAction.runWriteCommandAction(project.get()) {
                    editor.document.deleteString(closingQuote, closingQuote + 1)
                }
                PsiDocumentManager.getInstance(project.get()).commitAllDocuments()
                assertEquals(MipsTypes.psi("INCLUDE"), tokenType(directive))
                assertEquals(MipsTypes.psi("UNCLOSED_STRING"), tokenType(path))
                editor.caretModel.moveToOffset(path + 1)
                assertNull(fixture.getReferenceAtCaretPosition())
                WriteCommandAction.runWriteCommandAction(project.get()) {
                    editor.document.insertString(closingQuote, "\"")
                }
                PsiDocumentManager.getInstance(project.get()).commitAllDocuments()
                assertReferences()
            }
        }

    @Test
    fun `native file references preserve raw spaces question marks unicode and repeated separators`() = timeoutRunBlocking<Unit> {
        val files = withContext(Dispatchers.EDT) {
            val filename = if (SystemInfoRt.isWindows) " target #é.asm" else " target?é.asm "
            val raw = fixture.addFileToProject(filename, "target: nop\n")
            val child = fixture.addFileToProject("parts/child.s", "child: nop\n")
            val source = fixture.addFileToProject(
                "main.asm", ".include \"$filename\"\n.include \"./parts//child.s\"\n.include \"${child.virtualFile.path}\"\n",
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
            for ((index, literal) in strings.withIndex()) {
                val path = literal.text.substring(1, literal.textLength - 1)
                val references = MipsFileReferenceSet(path, literal)
                assertEquals(path, references.pathString)
                assertEquals(index == 2, references.isAbsolutePathReference)
            }
        }
    }

    @Test
    fun `Windows include paths preserve written ranges for relative drive and UNC paths`() = timeoutRunBlocking<Unit> {
        data class Include(val path: String, val absolute: Boolean, val segments: List<String>)

        val includes = listOf(
            Include(""".\parts\child.s""", false, listOf(".", "parts", "child.s")),
            Include("""\parts\child.s""", false, listOf("parts", "child.s")),
            Include("C:relative.s", false, listOf("C:relative.s")),
            Include("""C:\parts\child.s""", true, listOf("parts", "child.s")),
            Include("C:/parts//child.s", true, listOf("parts", "child.s")),
            Include("C:/", true, listOf("C:/")),
            Include("""\\server""", true, listOf("""\\server""")),
            Include("""\\server\share""", true, listOf("""\\server\share""")),
            Include("""\\server\share\parts\child.s""", true, listOf("parts", "child.s")),
            Include("""\\server\\share\\parts\child.s""", true, listOf("parts", "child.s")),
        )
        val (child, source) = withContext(Dispatchers.EDT) {
            val child = fixture.addFileToProject("parts/child.s", "child: nop\n")
            val text = includes.joinToString("\n") { ".include \"${it.path}\"" }
            child to fixture.addFileToProject("main.asm", text)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val literals = PsiTreeUtil.findChildrenOfType(source, MipsStringLiteral::class.java).toList()
            assertEquals(includes.size, literals.size)
            val sets = includes.zip(literals) { include, literal ->
                MipsFileReferenceSet(include.path, literal, windowsPaths = true).also { set ->
                    assertEquals(include.path, set.pathString)
                    assertEquals(include.absolute, set.isAbsolutePathReference, include.path)
                    assertEquals(include.segments, set.allReferences.map { it.rangeInElement.substring(literal.text) }, include.path)
                }
            }
            assertEquals(child, sets.first().allReferences.last().resolve())
            if (!SystemInfoRt.isWindows) assertTrue(sets[3].defaultContexts.isEmpty())
        }
    }

    @Test
    fun `include navigation and assembly stay at the root when following a symlink and parent segment`(
        @TempDir outsideProject: Path,
    ) = timeoutRunBlocking<Unit> {
        val (psi, target) = withContext(Dispatchers.EDT) {
            val directory = Files.createDirectory(outsideProject.resolve("actual"))
            val included = Files.writeString(directory.resolve("value.s"), ".eqv COUNT 7\n")
            val fromRoot = outsideProject.root.relativize(included)
            val path = Files.writeString(
                outsideProject.resolve("main.asm"),
                ".include \"shortcut/../../$fromRoot\"\naddiu \$t0, \$zero, COUNT\n",
            )
            val link = Files.createSymbolicLink(outsideProject.resolve("shortcut"), outsideProject.root)
            val manager = VirtualFileManager.getInstance()
            checkNotNull(manager.refreshAndFindFileByNioPath(link))
            val source = checkNotNull(manager.refreshAndFindFileByNioPath(path))
            fixture.configureFromExistingVirtualFile(source)
            fixture.file to checkNotNull(manager.refreshAndFindFileByNioPath(included))
        }
        readAction {
            val literal = PsiTreeUtil.findChildOfType(psi, MipsStringLiteral::class.java)!!
            val references = literal.references
            assertNotNull(references.first().resolve())
            for (index in 1..2) {
                val parent = assertInstanceOf(PsiDirectory::class.java, references[index].resolve())
                assertEquals(outsideProject.root, parent.virtualFile.toNioPath())
            }
            assertEquals(target, references.last().resolve()?.containingFile?.virtualFile)
        }
        val path = psi.virtualFile.toNioPath()
        val component = MarsSessionService.createMarsComponent()
        val disk = MarsSessionService.createMarsComponent().context().use { session ->
            session.assemble(listOf(path)).machineList.single().binaryStatement
        }
        component.context().use { session ->
            val capture = captureAssemblySources(listOf(path), null)
            assertEquals(setOf(path, target.toNioPath()), capture.revisions.keys)
            val result = capture.assembleIn(component)
            assertTrue(result.succeeded(), result.errors().toString())
            assertEquals(0x24080007, disk)
            assertEquals(disk, session.program.machineList.single().binaryStatement)
        }
    }

    @Test
    fun `include identity collapses directory aliases and keeps different file names`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val actual = Files.createDirectory(directory.resolve("actual"))
            val original = Files.writeString(actual.resolve("value.s"), ".include \"neighbor.s\"\n")
            val originalNeighbor = Files.writeString(actual.resolve("neighbor.s"), "addiu \$t0, \$zero, 7\n")
            val aliasNeighbor = Files.writeString(directory.resolve("neighbor.s"), "addiu \$t0, \$zero, 9\n")
            val folderAlias = Files.createSymbolicLink(directory.resolve("alias"), actual)
            val fileAlias = Files.createSymbolicLink(directory.resolve("alternate.s"), original)
            val duplicate = Files.writeString(directory.resolve("duplicate.asm"), """
                .include "actual/value.s"
                .include "alias/value.s"
            """.trimIndent())
            val distinct = Files.writeString(directory.resolve("distinct.asm"), """
                .include "actual/value.s"
                .include "alternate.s"
            """.trimIndent())
            val manager = VirtualFileManager.getInstance()
            val (duplicatePsi, distinctPsi) = withContext(Dispatchers.EDT) {
                listOf(original, originalNeighbor, aliasNeighbor, folderAlias.resolve("value.s"), fileAlias)
                    .forEach { manager.refreshAndFindFileByNioPath(it) }
                fun source(path: Path): MipsFile {
                    fixture.configureFromExistingVirtualFile(checkNotNull(manager.refreshAndFindFileByNioPath(path)))
                    return fixture.file as MipsFile
                }
                source(duplicate) to source(distinct)
            }
            val component = MarsSessionService.createMarsComponent()
            component.context().use { session ->
                val failure = assertThrows<ProcessingException> { session.assemble(listOf(duplicate)) }
                assertTrue(failure.errors().errorMessages.single().message.contains("Recursive include"))
                assertEquals(listOf(0x24080007, 0x24080009),
                    session.assemble(listOf(distinct)).machineList.map { it.binaryStatement })
            }
            val capture = captureAssemblySources(listOf(duplicate), null)
            assertEquals(capture.snapshot.includeIdentity(original), capture.snapshot.includeIdentity(folderAlias.resolve("value.s")),
                capture.snapshot.includeIdentities().toString())
            val capturedComponent = MarsSessionService.createMarsComponent()
            capturedComponent.context().use {
                val result = capture.assembleIn(capturedComponent)
                assertTrue(result.errors().single().message().contains("Recursive include"))
            }
            readAction {
                fun paths(file: MipsFile) = mipsSourceTimeline(file).sourceFiles().map { it.virtualFile.toNioPath() }.toList()
                assertTrue(mipsSourceTimeline(duplicatePsi).hasIncompleteIncludes)
                assertEquals(listOf(duplicate, original, originalNeighbor), paths(duplicatePsi))
                assertTrue(!mipsSourceTimeline(distinctPsi).hasIncompleteIncludes)
                assertEquals(listOf(distinct, original, originalNeighbor, fileAlias, aliasNeighbor), paths(distinctPsi))
            }
            withContext(Dispatchers.EDT) {
                fixture.configureFromExistingVirtualFile(duplicatePsi.virtualFile)
                val errors = fixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
                assertTrue(errors.any { it.description?.contains("Recursive include") == true }, errors.toString())
            }
        }

    @Test
    fun `include dot segments cannot traverse a regular file`() = timeoutRunBlocking<Unit> {
        val decoy = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("value.s", "nop\n")
            fixture.addFileToProject("decoy.s", "nop\n") as MipsFile
        }
        for ((index, request) in listOf("value.s/../decoy.s", "value.s/.").withIndex()) {
            val file = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("bad-$index.asm", ".include \"$request\"\n")
            }
            readAction {
                val literal = PsiTreeUtil.findChildOfType(file, MipsStringLiteral::class.java)!!
                assertNull(literal.references.last().resolve(), request)
            }
            val entry = file.virtualFile.toNioPath()
            val capture = captureAssemblySources(listOf(entry), null)
            assertEquals(setOf(entry), capture.revisions.keys)
            val component = MarsSessionService.createMarsComponent()
            val disk = MarsSessionService.createMarsComponent().let { disk ->
                disk.context().use { disk.assembler().assemble(listOf(entry)).errors().single() }
            }
            component.context().use {
                val error = capture.assembleIn(component).errors().single()
                assertEquals(entry, error.file())
                assertEquals(1, error.line())
                assertTrue(error.message().contains(request))
                assertEquals(disk, error)
            }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction { assertEquals(decoy.virtualFile.toNioPath(), inferredMipsAssemblyEntry(decoy)) }
        val valid = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("valid.asm", ".include \"./decoy.s\"\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction { assertEquals(valid.virtualFile.toNioPath(), inferredMipsAssemblyEntry(decoy)) }
    }

    @Test
    fun `an unresolved directory symlink has no usable parent segment`() {
        val unresolved = object : MockVirtualFile(true, "shortcut") {
            override fun `is`(property: VFileProperty) = property == VFileProperty.SYMLINK
            override fun getCanonicalFile(): VirtualFile? = null
        }
        assertNull(unresolved.resolveMipsDotSegment(".."))
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
            val rootPaths = if (SystemInfoRt.isWindows) listOf("C:/", "//") else listOf("/", "//")
            for (path in listOf("") + rootPaths) {
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
