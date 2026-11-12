package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsLabelReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `go to declaration finds a nested include and skips an unrelated export`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/outer.s", ".include \"inner.s\"\n")
            fixture.addFileToProject("parts/inner.s", "target.label: nop\n")
            fixture.addFileToProject("unrelated.s", ".globl target.label\ntarget.label: nop\n")
        }
        configure($$".include \"parts/outer.s\"\nentry: beq $t0, $zero, target.<caret>label\n")
        readAction {
            val reference = referenceAtCaret() as PsiPolyVariantReference
            assertTrue(reference.isSoft)
            val declaration = reference.resolve() as MipsDeclaration
            assertEquals("target.label", declaration.name)
            assertEquals("inner.s", declaration.containingFile.name)
            assertEquals(0, declaration.textOffset)
            assertEquals(declaration, GotoDeclarationAction.findTargetElement(project.get(), fixture.editor, fixture.editor.caretModel.offset))
        }
    }

    @Test
    fun `go to declaration uses the physical offset of a forward label in a plain include`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val contents = "# plain source\n    target.label: nop\n"
        val include = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/outer.txt", ".include \"inner.txt\"\n")
            fixture.addFileToProject("parts/inner.txt", contents)
        }
        configure("b target.<caret>label\n.include \"parts/outer.txt\"\n")
        val original = readAction {
            val reference = referenceAtCaret()!!
            val declaration = reference.resolve()!!
            assertEquals(include, declaration.containingFile)
            assertEquals("target.label", declaration.text)
            assertEquals(contents.indexOf("target.label"), declaration.textOffset)
            assertEquals(declaration, GotoDeclarationAction.findTargetElement(current, fixture.editor, fixture.editor.caretModel.offset))
            declaration
        }
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(include.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(current) { document.insertString(0, "\n") }
            PsiDocumentManager.getInstance(current).commitAllDocuments()
        }
        readAction {
            assertTrue(!original.isValid)
            assertEquals(original.textOffset + 1, referenceAtCaret()?.resolve()?.textOffset)
        }
    }

    @Test
    fun `macro local labels shadow outer labels including when introduced by an include`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("inner.s", "target: nop\n")
            fixture.addFileToProject("outer.s", ".include \"inner.s\"\n")
            fixture.addFileToProject("plain.txt", "  target: nop\n")
        }
        configure("target: nop\n.macro emit\n.include \"outer.s\"\nb tar<caret>get\n.end_macro\n")
        readAction { assertEquals("inner.s", referenceAtCaret()?.resolve()?.containingFile?.name) }
        configure("target: nop\n.macro emit\n.include \"plain.txt\"\nb tar<caret>get\n.end_macro\n")
        readAction { assertEquals("plain.txt", referenceAtCaret()?.resolve()?.containingFile?.name) }
        configure("target: nop\n.macro emit\n.include \"plain.txt\"\n.end_macro\nb tar<caret>get\n")
        readAction { assertEquals(fixture.file, referenceAtCaret()?.resolve()?.containingFile) }
        configure("target: nop\n.macro emit\ntarget: nop\nb tar<caret>get\n.end_macro\n")
        readAction { assertEquals(fixture.file.text.lastIndexOf("target:"), referenceAtCaret()?.resolve()?.textOffset) }
        configure("target: nop\n.macro emit\ntarget: nop\n.end_macro\nb tar<caret>get\n")
        readAction { assertEquals(0, referenceAtCaret()?.resolve()?.textOffset) }
        configure("target: nop\n.macro emit (%value, 7)\ntarget: nop\nb tar<caret>get\n.end_macro\n")
        readAction { assertEquals(fixture.file.text.lastIndexOf("target:"), referenceAtCaret()?.resolve()?.textOffset) }
        configure("target: nop\n.macro emit (%value, 7)\ntarget: nop\n.end_macro\nb tar<caret>get\n")
        readAction { assertEquals(0, referenceAtCaret()?.resolve()?.textOffset) }
    }

    @Test
    fun `ambiguous labels remain separate targets and unmatched names remain unresolved`() = timeoutRunBlocking<Unit> {
        configure("same: nop\nb sa<caret>me\nsame: nop\n")
        readAction {
            val reference = referenceAtCaret() as PsiPolyVariantReference
            assertNull(reference.resolve())
            assertEquals(listOf(0, fixture.file.text.lastIndexOf("same:")), reference.multiResolve(false).map { it.element?.textOffset })
        }
        configure("target: nop\nb Tar<caret>get\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }
        configure("n<caret>op: nop\n")
        readAction { assertNull(referenceAtCaret()) }
    }

    @Test
    fun `indexed label candidates keep include order and reflect unsaved edits`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val first = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("a.s", "target: nop\n")
            fixture.addFileToProject("outside.s", "target: nop\n")
            fixture.addFileToProject("z.s", "target: nop\n")
        }
        configure(".include \"z.s\"\n.include \"a.s\"\nb tar<caret>get\n")
        suspend fun targets() = readAction {
            (referenceAtCaret() as PsiPolyVariantReference).multiResolve(false).map { it.element?.containingFile?.name }
        }
        assertEquals(listOf("z.s", "a.s"), targets())
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(first.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(current) { document.replaceString(0, "target".length, "changed") }
            PsiDocumentManager.getInstance(current).commitDocument(document)
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        }
        assertEquals(listOf("a.s"), targets())
    }

    @Test
    fun `label binding follows assembler operand formats`() = timeoutRunBlocking<Unit> {
        configure($$"nop: nop\nlw $t0, n<caret>op+4($sp)\n")
        readAction { assertEquals(0, referenceAtCaret()?.resolve()?.textOffset) }
        configure($$"target: nop\n.eqv PAIR $t0, $t1\nentry: beq PAIR, tar<caret>get\n")
        readAction { assertEquals(0, referenceAtCaret()?.resolve()?.textOffset) }
        configure($$"target: nop\n.eqv OPEN (\nentry: lw $t0, tar<caret>get OPEN $sp)\n")
        readAction {
            val reference = referenceAtCaret()!!
            assertEquals(MipsTypes.psi("OPERAND_FRAGMENT"), reference.element.parent.node.elementType)
            assertEquals(0, reference.resolve()?.textOffset)
        }
        for (source in listOf($$"lw $t0, 4($s<caret>p)\n", "b invalid<caret>%name\n")) {
            configure(source)
            readAction { assertNull(referenceAtCaret(), source) }
        }
        for (source in listOf($$"sll $t0, $t1, shi<caret>ft\n", ".word shi<caret>ft\n")) {
            configure("shift: nop\n$source")
            readAction { assertNull((referenceAtCaret() as PsiPolyVariantReference).resolve(), source) }
        }
    }

    @Test
    fun `direct label resolution avoids active equivalence and macro parameter substitutions`() = timeoutRunBlocking<Unit> {
        configure("target: nop\n.eqv target somewhere\nb tar<caret>get\n")
        readAction { assertEquals(SymbolKind.CONSTANT, (referenceAtCaret()?.resolve() as MipsDeclaration).kind) }
        configure("target: nop\nb tar<caret>get\n.eqv target somewhere\n")
        readAction { assertEquals(0, referenceAtCaret()?.resolve()?.textOffset) }
        configure($$"$target: nop\n.macro emit ($target)\nb $tar<caret>get\n.end_macro\n")
        readAction { assertEquals(fixture.file.text.indexOf($$"$target", startIndex = 1), referenceAtCaret()?.resolve()?.textOffset) }
    }

    @Test
    fun `aliased instructions resolve label slots after expanding earlier operands`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val definitions = $$".eqv DEFINE .eqv\nDEFINE BRANCH bne $t0, $zero\n"
        val include = ".include \"aliases.s\"\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("aliases.s", definitions) }
        val branch = "entry: BRANCH tar<caret>get\ntarget: nop\n"
        configure(include + branch)
        readAction { assertEquals(fixture.file.text.indexOf("target:"), referenceAtCaret()?.resolve()?.textOffset) }
        configure(branch + include)
        readAction { assertNull(referenceAtCaret()?.resolve()) }

        configure(".eqv BR j\nBR n<caret>op\nnop: nop\n")
        readAction { assertEquals(fixture.file.text.indexOf("nop:"), referenceAtCaret()?.resolve()?.textOffset) }
        configure($$".eqv LOAD lw $t0\n.eqv OFFSET address+4\nLOAD OFFSET($ba<caret>se)\n$base: nop\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }
        configure($$".eqv LOAD lw $t0\nLOAD addr<caret>ess+4($sp)\naddress: nop\n")
        readAction { assertEquals(fixture.file.text.indexOf("address:"), referenceAtCaret()?.resolve()?.textOffset) }
        configure($$".eqv SHIFT sll $t0, $t1\nSHIFT shi<caret>ft\nshift: nop\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }

        directory.resolve("aliases.s").writeText(definitions)
        val entry = directory.resolve("entry.asm").also { it.writeText(include + branch.replace("<caret>", "")) }
        val program = project.get().service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
        assertEquals(listOf(0x15000000, 0), program.machineList!!.map { it.binaryStatement })
    }

    @Test
    fun `label navigation and usage search survive malformed earlier operands`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) { fixture.addFileToProject("target.inc", "target: nop\n") }
        configure(
          $$"""
            .include "target.inc"
            .eqv BR beq
            .eqv REG $t0
            .eqv FIRST SECOND
            .eqv SECOND FIRST
            BR $t0, 0x, tar<caret>get
            beq REG, 0x, target
            sll REG, 0x, target
            BR FIRST, $t0, target
        """.trimIndent())
        readAction {
            val reference = checkNotNull(referenceAtCaret())
            val target = reference.resolve() as? MipsDeclaration
            assertEquals("target.inc", target?.containingFile?.name)
            assertEquals(target, GotoDeclarationAction.findTargetElement(project.get(), fixture.editor, fixture.editor.caretModel.offset))
            val uses = fixture.findUsages(checkNotNull(target)).map { usage ->
                val element = checkNotNull(usage.reference).element
                fixture.editor.document.getLineNumber(element.textOffset) + 1
            }.sorted()
            assertEquals(listOf(6, 7), uses)
        }
    }

    @Test
    fun `label aliases resolve to the written source before assembly`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val aliases = ".eqv DEFINE .eqv\nDEFINE PREFIX generated:\nDEFINE ORIGINAL renamed\n"
        val body = "PREFIX nop\nORIGINAL: nop\n"
        val include = ".include \"aliases.s\"\n.include \"body.s\"\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("aliases.s", aliases)
            fixture.addFileToProject("body.s", body)
        }
        for ((spelling, written) in listOf("gene<caret>rated" to "PREFIX", "rena<caret>med" to "ORIGINAL")) {
            configure("${include}j $spelling\ninvalid %operand\n")
            readAction {
                val target = GotoDeclarationAction.findTargetElement(project.get(), fixture.editor, fixture.editor.caretModel.offset)
                assertEquals("body.s", target?.containingFile?.name)
                assertEquals(body.indexOf(written), target?.textOffset)
            }
        }
        withContext(Dispatchers.EDT) {
            DumbModeTestUtils.runInDumbModeSynchronously(project.get()) { assertNull(referenceAtCaret()?.resolve()) }
        }
        configure("j gene<caret>rated\n.include \"body.s\"\n.include \"aliases.s\"\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }
        val standalone = ".eqv ORIGINAL renamed\nORIGINAL:\nj rena<caret>med\n"
        configure(standalone)
        readAction { assertEquals(standalone.indexOf("ORIGINAL:"), referenceAtCaret()?.resolve()?.textOffset) }

        directory.resolve("aliases.s").writeText(aliases)
        directory.resolve("body.s").writeText(body)
        val entry = directory.resolve("entry.asm").also { it.writeText("${include}.globl generated, renamed\nj generated\n") }
        val context = project.get().service<MarsSessionService>().context
        context.assemble(listOf(entry), null, true, false)
        assertEquals(context.memory.textBaseAddress, context.symbolTable.getAddress("generated"))
        assertEquals(context.memory.textBaseAddress + 4, context.symbolTable.getAddress("renamed"))
    }

    @Test
    fun `a written label can expand into a data directive and replace its suffix`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            for (replacement in listOf(".data", "renamed: .data")) {
                val header = ".eqv OPEN $replacement\n"
                val line = "OPEN: .ascii \"ignored\"\n"
                val data = "target\ntarget: .word 42\n.text\nnop\n"
                val source = header + line + data
                configure(header + line + data.replaceFirst("target", "tar<caret>get"))
                readAction { assertEquals(source.indexOf("target:"), referenceAtCaret()?.resolve()?.textOffset, replacement) }
                configure(source + "j OP<caret>EN\n")
                readAction {
                    val use = referenceAtCaret()!!.element
                    val analysis = project.get().service<MarsAnalysisService>()
                    assertTrue(analysis.labelCompletionNames(fixture.file as MipsFile, use, "OP").isEmpty())
                }
                configure(header + line.replace("OPEN", "OP<caret>EN") + data)
                readAction {
                    val declaration = referenceAtCaret()?.resolve() as MipsDeclaration
                    assertEquals(SymbolKind.CONSTANT, declaration.kind)
                    assertEquals(listOf(header.length), fixture.findUsages(declaration).map { it.reference!!.element.textOffset })
                }
                if (replacement.startsWith("renamed")) {
                    configure(source + "j rena<caret>med\n")
                    readAction { assertEquals(header.length, referenceAtCaret()?.resolve()?.textOffset) }
                }
                val context = project.get().service<MarsSessionService>().context
                val entry = directory.resolve("label-alias.asm").also { it.writeText(source) }
                context.assemble(listOf(entry), null, true, false)
                val memory = context.memory
                val base = memory.dataBaseAddress
                assertEquals(base + 4, memory.getWordNoNotify(base))
                assertEquals(42, memory.getWordNoNotify(base + 4))
            }
        }

    @Test
    fun `a separator alias declares the written label before assembly`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val definitions = ".eqv SEP :\n"
        val body = $$"entry, SEP, addiu $t0, $zero, 7\naddiu SEP nop\n"
        val includes = ".include \"definitions.s\"\n.include \"body.s\"\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("definitions.s", definitions)
            fixture.addFileToProject("body.s", body)
        }
        configure(includes + "j ent<caret>ry\n")
        readAction {
            val target = referenceAtCaret()?.resolve()
            assertEquals("body.s", target?.containingFile?.name)
            assertEquals("entry", target?.text)
        }
        configure(".include \"body.s\"\n.include \"definitions.s\"\nj ent<caret>ry\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }
        configure(includes + "j add<caret>iu\n")
        readAction {
            val target = referenceAtCaret()?.resolve()
            assertEquals("body.s", target?.containingFile?.name)
            assertEquals(body.indexOf("addiu SEP"), target?.textOffset)
        }

        directory.resolve("definitions.s").writeText(definitions)
        directory.resolve("body.s").writeText(body)
        val entry = directory.resolve("entry.asm").also { it.writeText(includes + ".globl entry\nj entry\n") }
        val context = project.get().service<MarsSessionService>().context
        val program = context.assemble(listOf(entry), null, true, false)
        assertEquals(0x24080007, program.machineList!!.first().binaryStatement)
        assertEquals(context.memory.textBaseAddress, context.symbolTable.getAddress("entry"))
    }

    @Test
    fun `generated macro labels shadow outer labels only within their own expansion scope`() = timeoutRunBlocking<Unit> {
        val aliases = ".eqv INNER same:\n.eqv HIDDEN hidden:\n.eqv OPEN .macro emit\n.eqv CLOSE .end_macro\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("body.s", "INNER nop\nHIDDEN nop\n") }
        val body = "${aliases}same: nop\nOPEN\n.include \"body.s\"\n"
        configure("${body}j sa<caret>me\nCLOSE\n")
        readAction { assertEquals("body.s", referenceAtCaret()?.resolve()?.containingFile?.name) }
        configure("${body}CLOSE\nj sa<caret>me\n")
        readAction { assertEquals(body.indexOf("same: nop"), referenceAtCaret()?.resolve()?.textOffset) }
        configure("${body}CLOSE\nj hid<caret>den\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }
    }

    @Test
    fun `written labels follow expanded macro boundaries across included files`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val definitions = "same: nop\nhidden: nop\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("macro-body.s", definitions) }
        val prefix = ".eqv OPEN .macro emit\n.eqv CLOSE .end_macro\nsame: nop\n"
        val body = ".include \"macro-body.s\"\n"
        configure("${prefix}OPEN\n${body}j sa<caret>me\nCLOSE\n")
        readAction { assertEquals("macro-body.s", referenceAtCaret()?.resolve()?.containingFile?.name) }
        configure("${prefix}OPEN\n${body}CLOSE\nj sa<caret>me\n")
        readAction { assertEquals(prefix.indexOf("same: nop"), referenceAtCaret()?.resolve()?.textOffset) }
        configure("${prefix}OPEN\n${body}CLOSE\nj hid<caret>den\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }

        val entry = "${prefix}.macro emit\n${body}CLOSE\n.globl public\npublic: nop\nj pub<caret>lic\n"
        configure(entry)
        readAction { assertEquals(entry.indexOf("public:"), referenceAtCaret()?.resolve()?.textOffset) }
        directory.resolve("macro-body.s").writeText(definitions)
        val file = directory.resolve("entry.asm").also { it.writeText(entry.replace("<caret>", "")) }
        val context = project.get().service<MarsSessionService>().context
        context.assemble(listOf(file), null, true, false)
        assertEquals(context.memory.textBaseAddress + 4, context.symbolTable.getAddress("public"))
    }

    @Test
    fun `written labels survive failed substitutions and definitions that block expansion`() = timeoutRunBlocking<Unit> {
        configure("""
            .eqv physical expanded
            physical: .eqv LIMIT 7
            .eqv SWITCH .data
            SWITCH: nop
            .eqv cycleA cycleB
            .eqv cycleB cycleA
            cycleA: nop
            first: second: nop
            j <caret>
        """.trimIndent())
        readAction {
            val file = fixture.file as MipsFile
            val position = file.findElementAt(fixture.editor.caretModel.offset - 1)!!
            val names = file.project.service<MarsAnalysisService>().labelCompletionNames(file, position, "")
            assertEquals(setOf("physical", "cycleA", "first"), names)
        }
    }

    private suspend fun configure(source: String) {
        withContext(Dispatchers.EDT) { fixture.configureByText("labels.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
    }

    private fun referenceAtCaret() = fixture.file.findReferenceAt(fixture.editor.caretModel.offset)
}
