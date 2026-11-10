package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsEquivalenceReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `a short equivalence can acquire its definition from a prior alias`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val definition = ".eqv DECLARE VALUE 7\n"
            val shortForm = ".eqv DECLARE\n"
            val use = "addiu \$t0, \$zero, VALUE\n"
            val source = definition + shortForm + use
            val entry = directory.resolve("short-equivalence.asm").also { it.writeText(source) }
            val program = project.get().service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
            assertEquals(listOf(0x24080007), program.machineList.map { it.binaryStatement })

            val alias = target(definition + shortForm.replace("DECLARE", "DEC<caret>LARE") + use)
            assertEquals("DECLARE", alias?.name)
            assertEquals(SymbolKind.CONSTANT, alias?.kind)
            val generated = target(definition + shortForm + use.replace("VALUE", "VAL<caret>UE"))
            assertEquals(".eqv", generated?.name)
            assertEquals(definition.length, generated?.offset)
            assertNull(target(shortForm + definition + use.replace("VALUE", "VAL<caret>UE")))
        }

    @Test
    fun `go to declaration reaches the first definition in include expansion order`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/outer.s", ".include \"inner.s\"\n.eqv COUNT 4\n")
            fixture.addFileToProject("parts/inner.s", ".eqv COUNT 4\n")
        }
        val include = ".include \"parts/outer.s\"\n"
        val definition = ".eqv COUNT 4\n"
        val use = "li \$t0, CO<caret>UNT\n"
        assertEquals("inner.s", target(".include \"missing.s\"\n$include$definition$use")?.file)
        assertTrue(target("$definition$include$use")?.inEntry == true)
        assertNull(target("$use$include"))
        assertNull(target("$definition${use.replace("CO<caret>UNT", "co<caret>unt")}"))
    }

    @Test
    fun `equivalence lookup terminates on include cycles and waits for the index`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("cycle.s", ".include \"cycle.s\"\n.eqv COUNT 4\n")
            fixture.configureByText("entry.asm", ".include \"cycle.s\"\nli \$t0, CO<caret>UNT\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val file = fixture.file as MipsFile
            val position = file.findElementAt(fixture.editor.caretModel.offset)!!
            val analysis = current.service<MarsAnalysisService>()
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertNull(analysis.equivalenceTarget(file, position))
            }
            assertEquals("cycle.s", analysis.equivalenceTarget(file, position)?.containingFile?.name)
        }
    }

    @Test
    fun `equivalences bind in immediate segment macro argument and operation positions`() = timeoutRunBlocking<Unit> {
        for ((source, name) in listOf(
            ".eqv VALUE 4\nsll \$t0, \$t1, VA<caret>LUE\n" to "VALUE",
            ".eqv BASE 0x10010000\n.data BA<caret>SE\n" to "BASE",
            ".eqv EMIT nop\nEM<caret>IT\n" to "EMIT",
            ".eqv VALUE 4\nemit(VA<caret>LUE)\n" to "VALUE",
            ".eqv END )\nlw \$t0, 4(\$sp E<caret>ND\n" to "END",
            ".eqv VALUE 4\n.eqv ALIAS VALUE\nli \$t0, AL<caret>IAS\n" to "ALIAS",
            ".macro emit\n.eqv VALUE 4\n.end_macro\n.word VA<caret>LUE\n" to "VALUE",
        )) {
            val declaration = target(source)
            assertEquals(name, declaration?.name, source)
            assertEquals(SymbolKind.CONSTANT, declaration?.kind, source)
        }
        assertNull(target(".eqv VALUE 4\n.eqv ALIAS VA<caret>LUE\n"))
        assertNull(target(".eqv VALUE\nli \$t0, VA<caret>LUE\n"))
    }

    @Test
    fun `generated equivalences follow physical include order and use the first valid definition`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val generator = ".eqv DEFINE .eqv\n.eqv MAKE DEFINE\n"
        val definitions = "start: MAKE COUNT 7\nMAKE LIMIT COUNT\n"
        val include = ".include \"definitions.s\"\n"
        val use = "addiu \$t0, \$zero, LI<caret>MIT\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("definitions.s", definitions) }
        val source = "$generator$include$use"
        assertEquals(Target("definitions.s", "MAKE", null, false, definitions.lastIndexOf("MAKE")), target(source))
        assertNull(target("$generator$use$include"))
        assertEquals("MAKE", target("$generator$include.eqv LIMIT 9\n$use")?.name)
        assertEquals("LIMIT", target("$generator.eqv LIMIT 9\n$include$use")?.name)
        val malformed = "$generator.eqv LIMIT LIMIT\nMAKE LIMIT\nMAKE LIMIT LIMIT\n"
        assertEquals("MAKE", target("$malformed$include$use")?.name)
        assertNull(target("$malformed$use"))

        directory.resolve("definitions.s").writeText(definitions)
        val entry = directory.resolve("entry.asm").also { it.writeText(source.replace("<caret>", "")) }
        val program = project.get().service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
        assertEquals(listOf(0x24080007), program.machineList.map { it.binaryStatement })
    }

    @Test
    fun `macro operands that record equivalences are visible after the call`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val macro = ".macro discard (%first, %directive, %name, %value)\nnop\n.end_macro\n"
        val use = "addiu \$t0, \$zero, CO<caret>UNT\n"
        val context = project.get().service<MarsSessionService>().context
        for (directive in listOf(".eQv", "DEFINE")) {
            val call = "discard ignored $directive COUNT 7\n"
            val source = ".eqv DEFINE .eqv\n$macro$call$use"
            val entry = directory.resolve("entry.asm").also { it.writeText(source.replace("<caret>", "")) }
            val program = context.assemble(listOf(entry), null, true, false)
            assertEquals(listOf(0, 0x24080007), program.machineList.map { it.binaryStatement })
            val declaration = target(source)
            assertTrue(declaration?.inEntry == true)
            assertEquals(directive, declaration?.name)
            assertEquals(source.indexOf(directive, source.indexOf(call)), declaration?.offset)
            assertNull(target(".eqv DEFINE .eqv\n$macro$use$call"))
        }
    }

    @Test
    fun `generated lookups refresh when an include appears or its unsaved PSI changes`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = ".eqv DEFINE .eqv\n.include \"late.s\"\nli \$t0, CO<caret>UNT\n"
        assertNull(target(source))
        val included = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("late.s", "DEFINE COUNT 7\n").virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        assertEquals("late.s", targetAtCaret()?.file)
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(included)!!
            WriteCommandAction.runWriteCommandAction(current) {
                document.setText("DEFINE OTHER 7\n")
                PsiDocumentManager.getInstance(current).commitAllDocuments()
            }
        }
        assertNull(targetAtCaret())
    }

    @Test
    fun `included sources follow a unique entry before assembly and respect explicit entry selection`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = "li \$t0, VALUE\n"
        val include = ".include \"part.s\"\n"
        val firstSource = ".eqv VALUE 7\n$include"
        val secondSource = ".eqv VALUE 8\n$include"
        val (first, second, part) = withContext(Dispatchers.EDT) {
            val first = fixture.addFileToProject("first.asm", firstSource).virtualFile
            val second = fixture.addFileToProject("second.asm", ".eqv VALUE 8\n").virtualFile
            val part = fixture.addFileToProject("part.s", source).virtualFile
            fixture.configureFromExistingVirtualFile(part)
            fixture.editor.caretModel.moveToOffset(source.indexOf("VALUE"))
            Triple(first, second, part)
        }
        suspend fun edit(file: VirtualFile, text: String) {
            withContext(Dispatchers.EDT) {
                val document = FileDocumentManager.getInstance().getDocument(file)!!
                WriteCommandAction.runWriteCommandAction(current) {
                    document.setText(text)
                    PsiDocumentManager.getInstance(current).commitAllDocuments()
                }
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        assertEquals(first.name, targetAtCaret()?.file)
        withContext(Dispatchers.EDT) {
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                val file = fixture.file as MipsFile
                val timeline = current.service<MarsAnalysisService>().editorTimeline(file)
                assertFalse(timeline.sourceFiles().any { it.virtualFile == first })
            }
        }
        edit(first, "$include.eqv VALUE 7\n")
        assertNull(targetAtCaret())
        edit(first, firstSource)
        assertEquals(first.name, targetAtCaret()?.file)

        edit(second, secondSource)
        assertNull(targetAtCaret())
        val build = current.service<MarsBuildService>()
        build.assemble(second.toNioPath())
        assertEquals(second.name, targetAtCaret()?.file)

        edit(second, ".eqv VALUE 8\n")
        assertEquals(first.name, targetAtCaret()?.file)
        build.assemble(part.toNioPath())
        assertNull(targetAtCaret())
    }

    private data class Target(val file: String, val name: String?, val kind: SymbolKind?, val inEntry: Boolean, val offset: Int)

    private suspend fun target(source: String): Target? {
        withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        return targetAtCaret()
    }

    private suspend fun targetAtCaret(): Target? = readAction {
        val element = GotoDeclarationAction.findTargetElement(project.get(), fixture.editor, fixture.editor.caretModel.offset)
            ?: return@readAction null
        val declaration = element as? MipsDeclaration
        Target(element.containingFile.name, declaration?.name ?: element.text, declaration?.kind,
            element.containingFile == fixture.file, element.textOffset)
    }
}
