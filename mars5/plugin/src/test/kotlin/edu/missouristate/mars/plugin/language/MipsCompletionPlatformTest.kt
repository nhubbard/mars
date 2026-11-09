package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

@TestApplication
class MipsCompletionPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `statement heads offer every catalog instruction and directive once`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val names = (InstructionDefaults.get().instructionList.map { it.name } + Directives.getDirectiveList().map { it.name }).toSet()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("completion.asm", "  <caret>")
            val matches = fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it in names }
            assertEquals(names, matches.toSet())
            assertEquals(names.size, matches.size)
        }
    }

    @Test
    fun `catalog completion inserts a dotted mnemonic after a label and a directive inside a macro`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.configureByText("completion.asm", "entry: C.<caret>eq.s \$f0, \$f2\n")
            val instruction = fixture.completeBasic().orEmpty().single { it.lookupString == "c.eq.s" }
            val example = InstructionDefaults.get().instructionList.first { it.name == "c.eq.s" }.exampleFormat
            val presentation = LookupElementPresentation().also(instruction::renderElement)
            assertEquals(example.removePrefix("c.eq.s"), presentation.tailText)
            fixture.lookup.setCurrentItem(instruction)
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals("entry: c.eq.s \$f0, \$f2\n", fixture.file.text)

            fixture.configureByText("completion.asm", ".macro emit\n  .A<caret>\n.end_macro\n")
            val directive = fixture.completeBasic().orEmpty().single { it.lookupString == ".asciiz" }
            fixture.lookup.setCurrentItem(directive)
            fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertEquals(".macro emit\n  .asciiz\n.end_macro\n", fixture.file.text)
        }
    }

    @Test
    fun `macro completion displays the visible overloads and inserts the call name`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val include = """
            .macro emit (%obsolete)
              addiu ${'$'}t0, ${'$'}zero, 1
            .end_macro
            .macro emitPair
              nop
            .end_macro
        """.trimIndent()
        val source = """
            .include "macros.s"
            .eqv OPEN .macro
            .eqv CLOSE .end_macro
            OPEN emit (%value)
              addiu ${'$'}t0, ${'$'}zero, %value
            CLOSE
            .macro emit (%left, ${'$'}right)
              nop
            .end_macro
            entry: em<caret>itTail (7)
            .macro emitLater
              nop
            .end_macro
        """.trimIndent()
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("macros.s", include)
            fixture.configureByText("macro-completion.asm", source)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val completed = withContext(Dispatchers.EDT) {
            val choices = fixture.completeBasic().orEmpty().filter { it.lookupString.startsWith("emit") }
            assertEquals(setOf("emit", "emitPair"), choices.map { it.lookupString }.toSet())
            val item = choices.single { it.lookupString == "emit" }
            val presentation = LookupElementPresentation().also(item::renderElement)
            assertEquals(" (%value) / (%left, \$right)", presentation.tailText)
            fixture.lookup.setCurrentItem(item)
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            fixture.file.text.also { assertEquals(source.replace("em<caret>itTail", "emit"), it) }
        }

        directory.resolve("macros.s").writeText(include)
        val entry = directory.resolve("entry.asm").also { it.writeText(completed) }
        val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
        assertEquals(listOf(0x24080007), program.machineList.map { it.binaryStatement })
    }

    @Test
    fun `macro formal completion replaces the whole token after an aliased included header`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val current = project.get()
            val definitions = ".eqv OPEN .macro\n.eqv FORMALS (%value, %valueMore)\nOPEN emit FORMALS\n"
            val source = ".include \"formals.s\"\naddiu \$t0, \$zero, %va<caret>lueSuffix\n.end_macro\nemit(7, 0)\n"
            withContext(Dispatchers.EDT) {
                fixture.addFileToProject("formals.s", definitions)
                fixture.configureByText("formal-completion.asm", source)
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                val choices = fixture.completeBasic().orEmpty().filter { it.lookupString.startsWith('%') }
                assertEquals(setOf("%value", "%valueMore"), choices.map { it.lookupString }.toSet())
                fixture.lookup.setCurrentItem(choices.single { it.lookupString == "%value" })
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                fixture.file.text.also { assertEquals(source.replace("%va<caret>lueSuffix", "%value"), it) }
            }
            directory.resolve("formals.s").writeText(definitions)
            val entry = directory.resolve("entry.asm").also { it.writeText(completed) }
            val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
            assertEquals(listOf(0x24080007), program.machineList.map { it.binaryStatement })
        }

    @Test
    fun `formal suggestions follow token context case and macro scope`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val formals = setOf("%value", "%valueMore", "\$destination", "\$distance")
        val header = ".macro emit (${formals.joinToString()})\n"
        val end = ".end_macro\n"
        val percent = setOf("%value", "%valueMore")
        val spim = setOf("\$destination", "\$distance")
        val cases = listOf(
            "$header    <caret>\n$end" to formals,
            "$header    %<caret>\n$end" to percent,
            "$header    %va<caret>: nop\n$end" to percent,
            "$header    other(%va<caret>)\n$end" to percent,
            "$header    lw \$t0, 0(\$d<caret>)\n$end" to spim,
            "$header    .eqv \$destination 7\n    li \$t0, <caret>\n$end" to (formals - "\$destination"),
            "$header    li \$t0, %V<caret>\n$end" to emptySet(),
            "$header    li \$t0, VALUE<caret>\n$end" to emptySet(),
            "$header    .asciiz \"%va<caret>\"\n$end" to emptySet(),
            "$header    # %va<caret>\n$end" to emptySet(),
            "$header${end}li \$t0, %va<caret>\n" to emptySet(),
            ".macro emit (%va<caret>lue, %valueMore)\n$end" to emptySet(),
        )
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            for ((source, expected) in cases) {
                fixture.configureByText("formal-scope.asm", source)
                val names = fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it in formals }
                assertEquals(expected, names.toSet(), source)
            }
            fixture.configureByText("formal-registers.asm", "$header    li \$<caret>, 7\n$end")
            val names = fixture.completeBasic().orEmpty().map { it.lookupString }
            assertTrue("\$t0" in names && spim.all { it in names }, names.toString())
            val file = fixture.file as MipsFile
            val position = file.findElementAt(fixture.editor.caretModel.offset - 1)!!
            val analysis = current.service<MarsAnalysisService>()
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertTrue(analysis.macroParameterNames(file, position).isEmpty())
            }
        }
    }

    @Test
    fun `macro suggestions honor case active aliases and incomplete definitions`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val file = fixture.configureByText("macro-scope.asm", """
                .macro emit
                .end_macro
                .macro EmitCase
                .end_macro
                .macro emitShadowed
                .end_macro
                .eqv emitShadowed nop
                .macro emitInvalid 7
                .end_macro
                .macro ${'$'}emitDollar
                .end_macro
                .macro emitOpen
                  <caret>
            """.trimIndent()) as MipsFile
            val analysis = current.service<MarsAnalysisService>()
            val offset = fixture.editor.caretModel.offset
            assertEquals(mapOf("emit" to listOf("()")), analysis.macroCompletionSignatures(file, offset, "em"))
            assertEquals(setOf("EmitCase"), analysis.macroCompletionSignatures(file, offset, "Em").keys)
            assertEquals(setOf("\$emitDollar"), analysis.macroCompletionSignatures(file, offset, "\$em").keys)
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertTrue(analysis.macroCompletionSignatures(file, offset, "").isEmpty())
            }
            val dollarCall = ".macro \$emitDollar\n.end_macro\n.macro \$emitOther\n.end_macro\n\$em<caret>\n"
            fixture.configureByText("macro-dollar.asm", dollarCall)
            val dollarMacro = fixture.completeBasic().orEmpty().single { it.lookupString == "\$emitDollar" }
            fixture.lookup.setCurrentItem(dollarMacro)
            fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertEquals(dollarCall.replace("\$em<caret>", "\$emitDollar"), fixture.file.text)
        }
    }

    @Test
    fun `register completion follows the operand format and register bank`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fun registers(source: String): Set<String> {
                fixture.configureByText("registers.asm", "$source\n")
                return fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it.startsWith('$') }.toSet()
            }

            val integer = registers("entry: ADD <caret>")
            assertEquals(64, integer.size)
            assertTrue(integer.containsAll(listOf("\$t0", "\$zero", "\$0", "\$31")))
            assertEquals(integer, registers("lw \$t0, 4(<caret>)"))
            assertEquals(integer, registers("lw \$t0,, -4(<caret>"))
            assertEquals(integer, registers("lw \$t0, (<caret>)"))
            assertEquals(integer, registers("lw \$t0, address+4(<caret>)"))
            assertEquals(integer, registers("div \$t0 \$t1 <caret>"))
            assertEquals(integer, registers("mtc0 <caret>, \$12"))
            assertEquals(integer, registers(".eqv LOAD lw \$t0\nLOAD 4(<caret>)"))
            assertEquals(integer, registers(".eqv SEP :\nentry SEP lw \$t0, 4(<caret>)"))

            val floating = (0..31).map { "\$f$it" }.toSet()
            assertEquals(floating, registers("add.s \$f0, <caret>"))
            assertEquals(floating, registers("mtc1 \$t0, <caret>"))
            assertEquals(floating, registers(".eqv TRANSFER mtc1 \$t0\nTRANSFER <caret>"))
            assertEquals(floating, registers(".eqv SEP :\naddiu SEP mtc1 \$t0, <caret>"))

            val system = setOf("\$8", "\$12", "\$13", "\$14")
            assertEquals(system, registers("mfc0 \$t0, <caret>"))
            assertEquals(system, registers("mtc0 \$t0, <caret>"))
            assertEquals(system, registers(".eqv TRANSFER mtc0 \$t0\nTRANSFER <caret>"))

            for (source in listOf("lw \$t0, address <caret>", "sll \$t0, \$t1, <caret>", "nop <caret>", "emit <caret>")) {
                assertTrue(registers(source).isEmpty(), source)
            }
        }
    }

    @Test
    fun `completion follows operands and punctuation from unsaved included equivalences`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val current = project.get()
            val aliases = ".eqv ADDRESS OFFSET\n.eqv OFFSET -4(\n.eqv DEST \$f0\n.eqv PAIR \$t0, \$t1\n"
            val prefix = ".include \"outer.s\"\n"
            val source = prefix + "entry: lwc1 DEST, ADDRESS \$s<caret>9)\n"
            val inner = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("outer.s", ".include \"aliases.s\"\n")
                fixture.addFileToProject("aliases.s", aliases).virtualFile
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fixture.configureByText("operand-aliases.asm", source)
                val registers = fixture.completeBasic().orEmpty().filter { it.lookupString.startsWith('$') }
                assertEquals(((0..7).map { "\$s$it" } + "\$sp").toSet(), registers.map { it.lookupString }.toSet())
                fixture.lookup.setCurrentItem(registers.single { it.lookupString == "\$s2" })
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(source.replace("\$s<caret>9", "\$s2"), fixture.file.text)
                val completed = fixture.file.text

                val document = FileDocumentManager.getInstance().getDocument(inner)!!
                WriteCommandAction.runWriteCommandAction(current) {
                    document.setText(aliases.replace("-4(", "-4"))
                    PsiDocumentManager.getInstance(current).commitAllDocuments()
                }
                fixture.configureByText("operand-aliases.asm", source)
                assertTrue(fixture.completeBasic().orEmpty().none { it.lookupString.startsWith('$') })

                fixture.configureByText("branch-aliases.asm", prefix + "entry: beq PAIR, tar<caret>\ntarget: nop\ntarget2: nop\n")
                assertEquals(setOf("target", "target2"), fixture.completeBasic().orEmpty().map { it.lookupString }.toSet())
                completed
            }
            directory.resolve("outer.s").writeText(".include \"aliases.s\"\n")
            directory.resolve("aliases.s").writeText(aliases)
            val entry = directory.resolve("entry.asm").also { it.writeText(completed) }
            val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
            assertEquals(listOf(0xc640fffc.toInt()), program.machineList.map { it.binaryStatement })
        }

    @Test
    fun `register insertion replaces the complete token and preserves the address punctuation`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.configureByText("registers.asm", "lw \$t0, 4(\$t<caret>8)\n")
            val item = fixture.completeBasic().orEmpty().single { it.lookupString == "\$t2" }
            fixture.lookup.setCurrentItem(item)
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals("lw \$t0, 4(\$t2)\n", fixture.file.text)
        }
    }

    @Test
    fun `label completion follows current and included files and replaces a dotted target`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val included = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/inner.s", "Target.nested: nop\n.include \"outer.s\"\n")
            fixture.addFileToProject("unrelated.asm", ".globl Target.unrelated\nTarget.unrelated: nop\n")
            fixture.addFileToProject("parts/outer.s", "Target.included: nop\n.include \"inner.s\"\n")
        }
        withContext(Dispatchers.EDT) {
            fixture.configureByText("labels.asm", """
                .include "parts/outer.s"
                .include "missing.s"
                .eqv Target.constant 2
                .macro Target.macro
                  Target.private: nop
                .end_macro
                entry: beq ${'$'}t0, ${'$'}zero, Target.<caret>old
                Target.local: nop
                target.lowercase: nop
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(included.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(current) {
                document.setText("Target.updated: nop\n.include \"inner.s\"\n")
                PsiDocumentManager.getInstance(current).commitAllDocuments()
            }
            val matches = fixture.completeBasic().orEmpty().filter { it.lookupString.startsWith("Target.") }
            assertEquals(setOf("Target.local", "Target.nested", "Target.updated"), matches.map { it.lookupString }.toSet())
            assertTrue("target.lowercase" !in fixture.lookupElementStrings.orEmpty())
            fixture.lookup.setCurrentItem(matches.single { it.lookupString == "Target.updated" })
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertTrue(fixture.file.text.contains("entry: beq \$t0, \$zero, Target.updated\n"))
        }
    }

    @Test
    fun `labels inside macros stay in their physical macro scope`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("macro-part.s", "injectedLabel: nop\n\$includedParameterLabel: nop\n")
            fixture.addFileToProject("other-part.s", "hiddenLabel: nop\n")
            fixture.configureByText("macro.asm", """
                outerLabel: nop
                ${'$'}gccLabel: nop
                .macro other
                  .include "other-part.s"
                  otherLabel: nop
                .end_macro
                .macro emit (${'$'}parameterLabel, ${'$'}includedParameterLabel)
                  .include "macro-part.s"
                  currentLabel: nop
                  ${'$'}parameterLabel: nop
                  j <caret>
                .end_macro
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            val choices = fixture.completeBasic().orEmpty().filter { it.lookupString.endsWith("Label") }
            val (parameters, labels) = choices.partition {
                LookupElementPresentation().also(it::renderElement).typeText == "Macro parameter"
            }
            assertEquals(setOf("outerLabel", "\$gccLabel", "currentLabel", "injectedLabel"), labels.map { it.lookupString }.toSet())
            assertEquals(setOf("\$includedParameterLabel", "\$parameterLabel"), parameters.map { it.lookupString }.toSet())
        }
    }

    @Test
    fun `indexed label completion resumes when indexing finishes`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("targets.s", "targetOne: nop\ntargetTwo: nop\n")
            fixture.configureByText("indexing.asm", ".include \"targets.s\"\nj tar<caret>\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val file = fixture.file as MipsFile
            val position = file.findElementAt(fixture.editor.caretModel.offset - 1)!!
            val analysis = current.service<MarsAnalysisService>()
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertTrue(analysis.labelCompletionNames(file, position, "tar").isEmpty())
            }
            assertEquals(setOf("targetOne", "targetTwo"), analysis.labelCompletionNames(file, position, "tar"))
        }
    }

    @Test
    fun `completion exposes expanded labels and hides the replaced spelling`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("expanded-labels.asm", """
                .eqv PREFIX labelGenerated:
                .eqv labelOld labelRenamed
                .eqv PRIVATE labelPrivate:
                .eqv FORMAL ${'$'}labelParameter:
                .eqv OTHER unrelated:
                .eqv SEP :
                PREFIX nop
                labelSeparated SEP nop
                OTHER nop
                labelOld: nop
                .macro other
                  PRIVATE nop
                  FORMAL nop
                .end_macro
                j label<caret>
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val names = fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it.startsWith("label") }
            assertEquals(setOf("labelGenerated", "labelRenamed", "labelSeparated"), names.toSet())
        }
        withContext(Dispatchers.EDT) {
            fixture.configureByText("expanded-macro-labels.asm", """
                .eqv OPEN .macro emit
                .eqv CLOSE .end_macro
                .eqv PREFIX labelLocal:
                labelOuter: nop
                .macro prior
                  labelHidden: nop
                CLOSE
                OPEN
                  PREFIX nop
                  labelWritten: nop
                  j label<caret>
                CLOSE
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val names = fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it.startsWith("label") }
            assertEquals(setOf("labelLocal", "labelOuter", "labelWritten"), names.toSet())
        }
    }

    @Test
    fun `catalog heads do not complete in comments strings operands or declaration names`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            val sources = listOf("# <caret>", ".asciiz \"<caret>\"", "b <caret>", "<caret>: nop",
                ".eqv <caret> 1", ".macro <caret>\n.end_macro")
            for (source in sources) {
                fixture.configureByText("completion.asm", "$source\n")
                val names = fixture.completeBasic().orEmpty().map { it.lookupString }
                assertTrue("addiu" !in names && ".asciiz" !in names, source)
            }
        }
    }
}
