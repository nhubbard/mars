package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.LabelCompletionContext
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.writeText

@TestApplication
class MipsCompletionPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `statement heads offer enabled catalog instructions and directives once`(allowPseudo: Boolean) = timeoutRunBlocking {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val instructions = InstructionDefaults.get().instructionList
        val directives = Directives.getDirectiveList().map { it.name }
        val names = (instructions.map { it.name } + directives).toSet()
        val enabled = (instructions.filter { allowPseudo || it is BasicInstruction }.map { it.name } + directives).toSet()
        withContext(Dispatchers.EDT) {
            project.get().service<MarsAssemblySettings>().changeAssemblerOptions(allowPseudo, warningsAreErrors = false)
            fixture.configureByText("completion.asm", "  <caret>")
            val matches = fixture.completeBasic().orEmpty().filter { it.lookupString in names }
            assertEquals(enabled, matches.map { it.lookupString }.toSet())
            assertEquals(enabled.size, matches.size)
            val division = matches.single { it.lookupString == "div" }
            val presentation = LookupElementPresentation().also(division::renderElement)
            assertEquals($$" $t1,$t2", presentation.tailText)

            fixture.configureByText("completion.asm", $$"div $t0, $t1, <caret>\n")
            assertEquals(allowPseudo, fixture.completeBasic().orEmpty().any { it.lookupString == $$"$t2" })
            fixture.configureByText("completion.asm", $$".eqv DIVIDE div\nDIVIDE $t0, $t1, <caret>\n")
            assertEquals(allowPseudo, fixture.completeBasic().orEmpty().any { it.lookupString == $$"$t2" })
        }
    }

    @Test
    fun `bare-machine mnemonic completion shows numeric instruction examples`() = timeoutRunBlocking {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            val settings = project.get().service<MarsAssemblySettings>()
            settings.changeBareMachine(true)
            for (allowPseudo in listOf(true, false)) {
                settings.changeAssemblerOptions(allowPseudo, warningsAreErrors = false)
                fixture.configureByText("numeric-examples.asm", "  <caret>")
                val items = fixture.completeBasic().orEmpty().associateBy { it.lookupString }
                fun tail(name: String) = LookupElementPresentation().also(items.getValue(name)::renderElement).tailText
                assertEquals($$" $9,$10", tail("div"))
                assertEquals($$" $9,$8", tail("mtc0"))
                assertEquals($$" $f0,$f1,$f3", tail("add.s"))
                assertEquals("  " + Directives.TEXT.description, tail(".text"))
                assertEquals(allowPseudo, "li" in items)
                if (allowPseudo) assertEquals($$" $9,-100", tail("li"))
            }
        }
    }

    @Test
    fun `statement completion offers instruction aliases that fit written operands and project settings`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = $$".eqv RUN_OP sll\n.eqv RUN_ONE RUN_OP $t0,\n.eqv RUN_NUMBERED sll $8,\n" +
                $$".eqv RUN_PAIR sll $t0, $t1,\n.eqv RUN_ALL sll $t0, $t1, 4\n.eqv RUN_BAD sll $f0,\n" +
                $$".eqv RUN_PSEUDO li $t0,\n.eqv RUN_LABEL local:\n" +
                ".eqv RUN_CYCLE LOOP\n.eqv LOOP RUN_CYCLE\n"
            val include = ".include \"heads.inc\"\n"
            val suffix = "\n.eqv RUN_LATE nop\n"
            withContext(Dispatchers.EDT) { fixture.addFileToProject("heads.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun aliases(line: String): Set<String> {
                    fixture.configureByText("head-completion.mars", include + line + suffix)
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().filter { item -> item.typeText() == "Equivalence" }
                            .map { item -> item.lookupString }.filter { name -> name.startsWith("RUN_") }.toSet()
                    }
                }
                val basic = setOf("RUN_OP", "RUN_ONE", "RUN_NUMBERED", "RUN_PAIR", "RUN_ALL")
                assertEquals(basic + "RUN_PSEUDO", aliases("RUN_<caret>"))
                assertEquals(setOf("RUN_ONE", "RUN_NUMBERED"), aliases($$"entry: RUN_<caret>.old $t1, 4"))
                val unfinished = aliases($$"entry: RUN_<caret> $t1, 0x")
                assertTrue(unfinished.containsAll(setOf("RUN_ONE", "RUN_NUMBERED")))
                assertTrue("RUN_BAD" !in unfinished && "RUN_ALL" !in unfinished)
                assertTrue(aliases("entry: RUN_<caret> 0x, )").isEmpty())
                assertTrue(aliases("RUN_<caret> nop").isEmpty())
                val settings = current.service<MarsAssemblySettings>()
                settings.changeAssemblerOptions(false, warningsAreErrors = false)
                assertEquals(basic, aliases("RUN_<caret>"))
                settings.changeBareMachine(true)
                assertEquals(setOf("RUN_NUMBERED"), aliases($$"entry: RUN_<caret> $9, 4"))
                settings.changeBareMachine(false)

                assertEquals(setOf("RUN_ONE", "RUN_NUMBERED"), aliases($$"entry: RUN_<caret>.old $t1, 4"))
                val selected = fixture.lookup.items.single { it.lookupString == "RUN_ONE" }
                assertEquals($$" = RUN_OP $t0", LookupElementPresentation().also(selected::renderElement).tailText)
                fixture.lookup.currentItem = selected
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(include + $$"entry: RUN_ONE $t1, 4" + suffix, fixture.file.text)
                fixture.file.text
            }
            directory.resolve("heads.inc").writeText(definitions)
            val entry = directory.resolve("head-completion.mars").also { it.writeText(completed) }
            val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, extended = false, warnings = false)
            assertEquals(listOf(0x00094100), program.machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `statement completion offers expanded directives handled by the assembler`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = ".eqv DIRECT_WORD .WoRd\n.eqv DIRECT_VALUES DIRECT_WORD 7,\n" +
                ".eqv DIRECT_INCLUDE .include\n.eqv DIRECT_UNKNOWN .unknown\n.eqv DIRECT_DEFINE .eqv\n" +
                ".eqv DIRECT_TEXT .text\n.eqv DIRECT_NUMBER 42\n" +
                ".eqv VALUE_LIST .word valuee\n.eqv DIRECT_BROKEN VALUE_LIST-5\n"
            val before = ".include \"directives.inc\"\n.data\n"
            val after = $$"\nDIRECT_DEFINE SHIFT 4\nDIRECT_TEXT\nsll $t0, $t1, SHIFT\n.eqv DIRECT_LATE .space\n"
            withContext(Dispatchers.EDT) { fixture.addFileToProject("directives.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun aliases(line: String): Set<String> {
                    fixture.configureByText("directive-aliases.mars", before + line + after)
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().filter { item -> item.typeText() == "Equivalence" }
                            .map { item -> item.lookupString }.toSet()
                    }
                }
                assertEquals(setOf("DIRECT_WORD", "DIRECT_VALUES", "DIRECT_TEXT", "DIRECT_DEFINE"), aliases(".text\nDIRECT_<caret>"))
                assertTrue(aliases("DIRECT_I<caret> \"missing.inc\"").isEmpty())
                assertEquals(setOf("DIRECT_VALUES"), aliases("values: DIRECT_V<caret>.old 9"))
                val selected = fixture.lookup.items.single { it.lookupString == "DIRECT_VALUES" }
                assertEquals(" = DIRECT_WORD 7", LookupElementPresentation().also(selected::renderElement).tailText)
                fixture.lookup.currentItem = selected
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(before + "values: DIRECT_VALUES 9" + after, fixture.file.text)
                fixture.file.text
            }
            directory.resolve("directives.inc").writeText(definitions)
            val entry = directory.resolve("directive-aliases.mars").also { it.writeText(completed) }
            val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, extended = true, warnings = false)
            assertEquals(listOf(0x00094100), program.machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `statement completion offers macro aliases with compatible call arity`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = $$".macro emit\n.end_macro\n.macro emit (%dst, %amount)\nsll %dst, $t1, %amount\n.end_macro\n" +
                $$".eqv CALL_OP emit\n.eqv CALL_FIRST CALL_OP $t0\n.eqv CALL_WRAPPED emit($t0, 4)\n" +
                $$".eqv CALL_SHORT emit($t0)\n.eqv CALL_LONG emit $t0, 4, 5\n.eqv CALL_OPEN emit(\n" +
                ".eqv CALL_UNKNOWN later\n.eqv BROKEN emit valuee\n.eqv ZERO emit()\n"
            val include = ".include \"macro-aliases.inc\"\n"
            val suffix = "\n.macro emit (%later)\n.end_macro\n.macro later\n.end_macro\n"
            withContext(Dispatchers.EDT) { fixture.addFileToProject("macro-aliases.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun aliases(line: String): Set<String> {
                    fixture.configureByText("macro-alias-completion.mars", include + line + suffix)
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().filter { item -> item.typeText() == "Equivalence" }
                            .map { item -> item.lookupString }.toSet()
                    }
                }
                assertEquals(setOf("CALL_OP", "CALL_FIRST", "CALL_WRAPPED", "CALL_OPEN"), aliases("CALL_<caret>"))
                assertEquals(setOf("CALL_OP"), aliases($$"CALL_<caret>($t0, 4)"))
                assertEquals(setOf("CALL_OP", "CALL_OPEN"), aliases($$"CALL_<caret>($t0"))
                assertTrue(aliases($$"CALL_<caret> $t0, 4, 9").isEmpty())
                assertTrue(aliases("BROKEN<caret>-5").isEmpty())
                assertEquals(setOf("ZERO"), aliases("ZERO<caret>"))
                assertEquals(setOf("CALL_OP", "CALL_FIRST", "CALL_OPEN"), aliases("entry: CALL_<caret>.old, 4"))
                val selected = fixture.lookup.items.single { it.lookupString == "CALL_FIRST" }
                assertEquals($$" = CALL_OP $t0", LookupElementPresentation().also(selected::renderElement).tailText)
                fixture.lookup.currentItem = selected
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(include + "entry: CALL_FIRST, 4" + suffix, fixture.file.text)
                fixture.file.text
            }
            directory.resolve("macro-aliases.inc").writeText(definitions)
            val entry = directory.resolve("macro-alias-completion.mars").also { it.writeText(completed) }
            val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, extended = true, warnings = false)
            assertEquals(listOf(0x00094100), program.machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `catalog completion inserts a dotted mnemonic after a label and a directive inside a macro`() = timeoutRunBlocking {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.configureByText("completion.asm", $$"entry: C.<caret>eq.s $f0, $f2\n")
            val instruction = fixture.completeBasic().orEmpty().single { it.lookupString == "c.eq.s" }
            val example = InstructionDefaults.get().instructionList.first { it.name == "c.eq.s" }.exampleFormat
            val presentation = LookupElementPresentation().also(instruction::renderElement)
            assertEquals(example.removePrefix("c.eq.s"), presentation.tailText)
            fixture.lookup.currentItem = instruction
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals($$"entry: c.eq.s $f0, $f2\n", fixture.file.text)

            fixture.configureByText("completion.asm", ".macro emit\n  .A<caret>\n.end_macro\n")
            val directive = fixture.completeBasic().orEmpty().single { it.lookupString == ".asciiz" }
            fixture.lookup.currentItem = directive
            fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertEquals(".macro emit\n  .asciiz\n.end_macro\n", fixture.file.text)
        }
    }

    @Test
    fun `macro completion displays the visible overloads and inserts the call name`(@TempDir directory: Path) = timeoutRunBlocking {
        val include = $$"""
            .macro emit (%obsolete)
              addiu $t0, $zero, 1
            .end_macro
            .macro emitPair
              nop
            .end_macro
        """.trimIndent()
        val source = $$"""
            .include "macros.s"
            .eqv OPEN .macro
            .eqv CLOSE .end_macro
            OPEN emit (%value)
              addiu $t0, $zero, %value
            CLOSE
            .macro emit (%left, $right)
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
            assertEquals($$" (%value) / (%left, $right)", presentation.tailText)
            fixture.lookup.currentItem = item
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            fixture.file.text.also { assertEquals(source.replace("em<caret>itTail", "emit"), it) }
        }

        directory.resolve("macros.s").writeText(include)
        val entry = directory.resolve("entry.asm").also { it.writeText(completed) }
        val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, extended = true, warnings = false)
        assertEquals(listOf(0x24080007), program.machineList!!.map { it.binaryStatement })
    }

    @Test
    // Kotlin exposes the reported Java setters as read-only properties.
    @Suppress("UsePropertyAccessSyntax")
    fun `completion reads nested plain includes and refreshes their signatures after an edit`() = timeoutRunBlocking {
        val source = ".include \"lib/wrapper.txt\"\nj generated\n.data\n.word COUNT, storage\n.text\nem<caret>\n"
        val definitions = """
            .eqv NAME emit
            .eqv FORMALS (%first, %second)
            .macro NAME FORMALS
            .end_macro
            .macro emitPair
            .end_macro
            .eqv COUNT 4
            .eqv LABEL generated:
            LABEL nop
            .extern storage 4
        """.trimIndent()
        val current = project.get()
        val inner = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("lib/wrapper.txt", ".include \"inner.txt\"\n")
            val inner = fixture.addFileToProject("lib/inner.txt", definitions)
            fixture.configureByText("plain-completion.asm", source)
            inner
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            fun assertChoices(expected: String) {
                val choices = fixture.completeBasic().orEmpty().filter { it.lookupString.startsWith("emit") }
                assertEquals(setOf("emit", "emitPair"), choices.map { it.lookupString }.toSet())
                val item = choices.single { it.lookupString == "emit" }
                assertEquals(expected, LookupElementPresentation().also(item::renderElement).tailText)
                fixture.lookup.hideLookup(true)
            }
            assertChoices(" (%first, %second)")
            for ((use, declaration) in mapOf("generated" to "LABEL nop", "COUNT" to "COUNT 4", "storage" to "storage 4")) {
                val target = fixture.file.findReferenceAt(source.indexOf(use))!!.resolve()!!
                assertEquals(inner.virtualFile, target.containingFile.virtualFile)
                assertEquals(definitions.indexOf(declaration), target.textOffset)
            }
            val document = FileDocumentManager.getInstance().getDocument(inner.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(current) { document.setText(definitions.replace("%second", "%updated")) }
            PsiDocumentManager.getInstance(current).commitAllDocuments()
            assertChoices(" (%first, %updated)")
        }
    }

    @Test
    fun `macro formal completion replaces the whole token after an aliased included header`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = ".eqv OPEN .macro\n.eqv FORMALS (%value, %valueMore)\nOPEN emit FORMALS\n"
            val source = $$".include \"formals.s\"\naddiu $t0, $zero, %va<caret>lueSuffix\n.end_macro\nemit(7, 0)\n"
            withContext(Dispatchers.EDT) {
                fixture.addFileToProject("formals.s", definitions)
                fixture.configureByText("formal-completion.asm", source)
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                val choices = fixture.completeBasic().orEmpty().filter { it.lookupString.startsWith('%') }
                assertEquals(setOf("%value", "%valueMore"), choices.map { it.lookupString }.toSet())
                fixture.lookup.currentItem = choices.single { it.lookupString == "%value" }
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                fixture.file.text.also { assertEquals(source.replace("%va<caret>lueSuffix", "%value"), it) }
            }
            directory.resolve("formals.s").writeText(definitions)
            val entry = directory.resolve("entry.asm").also { it.writeText(completed) }
            val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, extended = true, warnings = false)
            assertEquals(listOf(0x24080007), program.machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `formal suggestions follow token context case and macro scope`() = timeoutRunBlocking {
        val current = project.get()
        val formals = setOf("%value", "%valueMore", $$"$destination", $$"$distance")
        val header = ".macro emit (${formals.joinToString()})\n"
        val end = ".end_macro\n"
        val percent = setOf("%value", "%valueMore")
        val spim = setOf($$"$destination", $$"$distance")
        val cases = listOf(
            "$header    <caret>\n$end" to formals,
            "$header    %<caret>\n$end" to percent,
            $$"$$header    li $t0, %<caret>\n$$end" to percent,
            "$header    %va<caret>: nop\n$end" to percent,
            "$header    other(%va<caret>)\n$end" to percent,
            $$"$$header    lw $t0, 0($d<caret>)\n$$end" to spim,
            $$"$$header    .eqv $destination 7\n    li $t0, <caret>\n$$end" to (formals - $$"$destination"),
            $$"$$header    .eqv $d<caret> 7\n$$end" to spim,
            "$header    .eqv CONSTANT %va<caret>\n$end" to percent,
            $$"$$header    .eqv $destination 7\n    .eqv CONSTANT $d<caret>\n$$end" to spim,
            $$"$$header    li $t0, %V<caret>\n$$end" to emptySet(),
            $$"$$header    li $t0, VALUE<caret>\n$$end" to emptySet(),
            "$header    .asciiz \"%va<caret>\"\n$end" to emptySet(),
            "$header    # %va<caret>\n$end" to emptySet(),
            $$"$$header$${end}li $t0, %va<caret>\n" to emptySet(),
            ".macro emit (%va<caret>lue, %valueMore)\n$end" to emptySet(),
        )
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            for ((source, expected) in cases) {
                fixture.configureByText("formal-scope.asm", source)
                val names = fixture.completeBasic().orEmpty().filter { it.lookupString in formals && it.typeText() == "Macro parameter" }
                    .map { it.lookupString }
                assertEquals(expected, names.toSet(), source)
            }
            fixture.configureByText("formal-registers.asm", $$"$$header    li $<caret>, 7\n$$end")
            val names = fixture.completeBasic().orEmpty().map { it.lookupString }
            assertTrue($$"$t0" in names && spim.all { it in names }, names.toString())
            val file = fixture.file as MipsFile
            val position = file.findElementAt(fixture.editor.caretModel.offset - 1)!!
            val analysis = current.service<MarsAnalysisService>()
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertTrue(analysis.macroParameterNames(file, position).isEmpty())
            }
        }
    }

    @Test
    fun `macro suggestions honor case active aliases and incomplete definitions`() = timeoutRunBlocking {
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val file = fixture.configureByText("macro-scope.asm", $$"""
                .macro emit
                .end_macro
                .macro EmitCase
                .end_macro
                .macro emitShadowed
                .end_macro
                .eqv emitShadowed nop
                .macro emitInvalid 7
                .end_macro
                .macro $emitDollar
                .end_macro
                .macro emitOpen
                  <caret>
            """.trimIndent()) as MipsFile
            val analysis = current.service<MarsAnalysisService>()
            val offset = fixture.editor.caretModel.offset
            assertEquals(mapOf("emit" to listOf("()")), analysis.macroCompletionSignatures(file, offset, "em"))
            assertEquals(setOf("EmitCase"), analysis.macroCompletionSignatures(file, offset, "Em").keys)
            assertEquals(setOf($$"$emitDollar"), analysis.macroCompletionSignatures(file, offset, $$"$em").keys)
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertTrue(analysis.macroCompletionSignatures(file, offset, "").isEmpty())
            }
            val dollarCall = $$".macro $emitDollar\n.end_macro\n.macro $emitOther\n.end_macro\n$em<caret>\n"
            fixture.configureByText("macro-dollar.asm", dollarCall)
            val dollarMacro = fixture.completeBasic().orEmpty().single { it.lookupString == $$"$emitDollar" }
            fixture.lookup.currentItem = dollarMacro
            fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertEquals(dollarCall.replace($$"$em<caret>", $$"$emitDollar"), fixture.file.text)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `register completion follows the operand format register bank and bare-machine mode`(bareMachine: Boolean) = timeoutRunBlocking {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            project.get().service<MarsAssemblySettings>().changeBareMachine(bareMachine)
            fun registers(source: String): Set<String> {
                fixture.configureByText("registers.asm", "$source\n")
                return fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it.startsWith('$') }.toSet()
            }

            val integer = registers("entry: ADD <caret>")
            val numbers = (0..31).map { $$"$$$it" }.toSet()
            if (bareMachine) {
                assertEquals(numbers, integer)
            } else {
                assertEquals(64, integer.size)
                assertTrue(integer.containsAll(numbers + setOf($$"$t0", $$"$zero")))
            }
            assertEquals(integer, registers($$"lw $t0, 4(<caret>)"))
            assertEquals(integer, registers($$"lw $t0,, -4(<caret>"))
            assertEquals(integer, registers($$"lw $t0, (<caret>)"))
            assertEquals(integer, registers($$"lw $t0, address+4(<caret>)"))
            assertEquals(integer, registers($$"div $t0 $t1 <caret>"))
            assertEquals(integer, registers($$"mtc0 <caret>, $12"))
            assertEquals(integer, registers($$".eqv LOAD lw $t0\nLOAD 4(<caret>)"))
            assertEquals(integer, registers($$".eqv SEP :\nentry SEP lw $t0, 4(<caret>)"))

            val floating = (0..31).map { $$"$f$$it" }.toSet()
            assertEquals(floating, registers($$"add.s $f0, <caret>"))
            assertEquals(floating, registers($$"mtc1 $t0, <caret>"))
            assertEquals(floating, registers($$".eqv TRANSFER mtc1 $t0\nTRANSFER <caret>"))
            assertEquals(floating, registers($$".eqv SEP :\naddiu SEP mtc1 $t0, <caret>"))

            val system = setOf($$"$8", $$"$12", $$"$13", $$"$14")
            assertEquals(system, registers($$"mfc0 $t0, <caret>"))
            assertEquals(system, registers($$"mtc0 $t0, <caret>"))
            assertEquals(system, registers($$".eqv TRANSFER mtc0 $t0\nTRANSFER <caret>"))

            for (source in listOf($$"lw $t0, address <caret>", $$"sll $t0, $t1, <caret>", "nop <caret>", "emit <caret>")) {
                assertTrue(registers(source).isEmpty(), source)
            }
        }
    }

    @Test
    // Kotlin exposes the reported Java setters as read-only properties.
    @Suppress("UsePropertyAccessSyntax")
    fun `completion follows operands and punctuation from unsaved included equivalences`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val aliases = $$".eqv ADDRESS OFFSET\n.eqv OFFSET -4(\n.eqv DEST $f0\n.eqv PAIR $t0, $t1\n"
            val prefix = ".include \"outer.s\"\n"
            val source = prefix + $$"entry: lwc1 DEST, ADDRESS $s<caret>9)\n"
            val inner = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("outer.s", ".include \"aliases.s\"\n")
                fixture.addFileToProject("aliases.s", aliases).virtualFile
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fixture.configureByText("operand-aliases.asm", source)
                val registers = fixture.completeBasic().orEmpty().filter { it.lookupString.startsWith('$') }
                assertEquals(((0..7).map { $$"$s$$it" } + $$"$sp").toSet(), registers.map { it.lookupString }.toSet())
                fixture.lookup.currentItem = registers.single { it.lookupString == $$"$s2" }
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(source.replace($$"$s<caret>9", $$"$s2"), fixture.file.text)
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
            val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, extended = true, warnings = false)
            assertEquals(listOf(0xc640fffc.toInt()), program.machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `completion follows malformed operands in instruction aliases`() = timeoutRunBlocking {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            val labels = "\ntarget: nop\ntarget2: nop\n"
            fun candidates(source: String): Set<String> {
                fixture.configureByText("incomplete-alias.mars", source)
                return fixture.completeBasic().orEmpty().map { it.lookupString }.toSet()
            }
            val branch = $$".eqv BR beq\nBR $t0, 0x, tar<caret>$$labels"
            assertEquals(setOf("target", "target2"), candidates(branch))
            fixture.lookup.currentItem = fixture.lookup.items.single { it.lookupString == "target" }
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals(branch.replace("tar<caret>", "target"), fixture.file.text)
            assertEquals(setOf("target", "target2"), candidates($$".eqv REG $t0\nbeq REG, 0x, tar<caret>$$labels"))
            assertEquals(setOf($$"$8", $$"$12", $$"$13", $$"$14"), candidates(".eqv TRANSFER mtc0\nTRANSFER 0x, $<caret>\n"))
            assertTrue(candidates($$".eqv BR beq\n.eqv FIRST SECOND\n.eqv SECOND FIRST\nBR FIRST, $t0, tar<caret>$$labels")
                .none { it.startsWith("target") })
        }
    }

    @Test
    fun `integer data completion follows includes aliases and continuation lines`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val setup = ".data\n.eqv WORDS .word\n.eqv SUFFIX :2\n.eqv SEPARATOR :\n.word 0\n"
            val prefix = ".include \"setup.inc\"\n"
            val labels = "\n.text\ntargetOne: nop\ntargetTwo: nop\n"
            withContext(Dispatchers.EDT) { fixture.addFileToProject("setup.inc", setup) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun candidates(line: String): Set<String> {
                    fixture.configureByText("data-completion.mars", prefix + line + labels)
                    return fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it.startsWith("target") }.toSet()
                }
                val expected = setOf("targetOne", "targetTwo")
                for (line in listOf(".word tar<caret>", ".half 1, tar<caret>", ".byte tar<caret>", "WORDS tar<caret>",
                    "tar<caret>", "2, tar<caret>", "slot: WORDS tar<caret>")) {
                    assertEquals(expected, candidates(line), line)
                }
                for (line in listOf(".float tar<caret>", ".text\n.word tar<caret>", ".float 0\n2, tar<caret>",
                    ".extern targetExtern 4\n.globl targetExt<caret>",
                    ".word tar<caret>:2", ".word (tar<caret>)", ".word tar<caret> SUFFIX",
                    ".word 7 SEPARATOR tar<caret>", "99 tar<caret> SUFFIX", "row: .word <caret> SUFFIX",
                    ".macro emit (%arg)\n.end_macro\nemit tar<caret>")) {
                    assertTrue(candidates(line).isEmpty(), line)
                }
                assertEquals(expected, candidates("WORDS target<caret>.old"))
                fixture.lookup.currentItem = fixture.lookup.items.single { it.lookupString == "targetTwo" }
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(prefix + "WORDS targetTwo" + labels, fixture.file.text)
                fixture.file.text
            }
            directory.resolve("setup.inc").writeText(setup)
            val entry = directory.resolve("data-completion.mars").also { it.writeText(completed) }
            val context = current.service<MarsSessionService>().context
            val program = context.assemble(listOf(entry), null, extended = true, warnings = false)
            val memory = context.memory
            assertEquals(program.machineList!!.last().address, memory.getWordNoNotify(memory.dataBaseAddress + Int.SIZE_BYTES))
        }

    @Test
    fun `integer data completion uses spellings the assembler can consume as addresses`(@TempDir directory: Path) = timeoutRunBlocking {
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val completed = withContext(Dispatchers.EDT) {
            val definitions = ".text\ntargetPlain: nop\ntargetShadow: nop\ntargetValue: nop\nnop: nop\n.local: nop\n" +
                $$".eqv targetShadow $t0\n.eqv targetValue 12\n.data\n.word 0\n"
            fun items(line: String): List<LookupElement> {
                fixture.configureByText("data-address-spellings.mars", definitions + line + "\n")
                return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                    it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                    fixture.completeBasic().orEmpty().toList()
                }
            }
            for (line in listOf("target<caret> 2 3", ".word target<caret>", "2, target<caret>")) {
                val matches = items(line).filter { it.lookupString.startsWith("target") }
                assertEquals(setOf("targetPlain", "targetValue"), matches.map { it.lookupString }.toSet(), line)
                assertEquals("Equivalence", matches.single { it.lookupString == "targetValue" }.typeText(), line)
            }
            assertTrue(items(".word no<caret>").none { it.lookupString == "nop" })
            assertTrue(items(".word .lo<caret>").any { it.lookupString == ".local" })
            assertTrue(items("<caret>targetPlain 2 3").none { it.lookupString == ".local" })
            items("target<caret> 2 3")
            fixture.lookup.currentItem = fixture.lookup.items.single { it.lookupString == "targetPlain" }
            fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertEquals(definitions + "targetPlain 2 3\n", fixture.file.text)
            fixture.file.text
        }
        val entry = directory.resolve("data-address-spellings.mars").also { it.writeText(completed) }
        val context = current.service<MarsSessionService>().context
        val program = context.assemble(listOf(entry), null, extended = true, warnings = false)
        val memory = context.memory
        assertEquals(program.machineList!!.first().address, memory.getWordNoNotify(memory.dataBaseAddress + Int.SIZE_BYTES))
    }

    @Test
    fun `a shared macro and label name completes as the role selected by the written arguments`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val definitions = $$"""
                .macro shared (%left, %right)
                  .text
                  addiu $t0, $zero, %left
                  .data
                .end_macro
                .eqv PAIR 2, 3
                .eqv CYCLE OTHER
                .eqv OTHER CYCLE
                .data
                .word 0
            """.trimIndent() + "\n"
            val suffix = "\n.globl shared\nshared: .word 0\nsharedOther: .word 0\n"
            val completed = withContext(Dispatchers.EDT) {
                fun choices(line: String): List<LookupElement> {
                    fixture.configureByText("shared-completion.mars", definitions + line + suffix)
                    return fixture.completeBasic().orEmpty().filter { it.lookupString == "shared" }
                }
                val macro = choices("sha<caret> PAIR").single()
                assertEquals(" (%left, %right)", LookupElementPresentation().also(macro::renderElement).tailText)
                val cycle = fixture.configureByText("shared-completion.mars", definitions + "sha CYCLE" + suffix) as MipsFile
                val range = TextRange.from(definitions.length, "sha".length)
                assertTrue(!current.service<MarsAnalysisService>().completesDataHeadAsMacro(cycle, range, "shared"))
                val label = choices("sha<caret> 7").single()
                assertNull(LookupElementPresentation().also(label::renderElement).tailText)
                fixture.lookup.currentItem = label
                fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
                val dataSource = fixture.file.text
                val call = choices("sha<caret> PAIR").single()
                fixture.lookup.currentItem = call
                fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
                dataSource to fixture.file.text
            }
            val context = current.service<MarsSessionService>().context
            val dataEntry = directory.resolve("data.mars").also { it.writeText(completed.first) }
            assertTrue(context.assemble(listOf(dataEntry), null, extended = true, warnings = false).machineList!!.isEmpty())
            val memory = context.memory
            assertEquals(context.symbolTable.getAddress("shared"), memory.getWordNoNotify(memory.dataBaseAddress + Int.SIZE_BYTES))
            val macroEntry = directory.resolve("call.mars").also { it.writeText(completed.second) }
            val program = context.assemble(listOf(macroEntry), null, extended = true, warnings = false)
            assertEquals(listOf(0x24080002), program.machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `integer data operands complete aliases without changing written values`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = ".eqv WORDS .word\n.eqv VALUE_NUM 3\n.eqv VALUE_ZERO 0\n.eqv VALUE_NEG -2\n" +
                ".eqv VALUE_LIST VALUE_NUM, 8\n.eqv VALUE_SYMBOL target\n.eqv VALUE_DOTTED .local\n" +
                $$".eqv VALUE_FLOAT 1.5\n.eqv VALUE_REG $t0\n.eqv VALUE_TEXT \"text\"\n" +
                ".eqv VALUE_LABEL generated:\n.eqv VALUE_MACRO emit\n.macro emit\n.end_macro\n"
            val include = ".include \"values.inc\"\n"
            val before = "$include.data\n"
            val after = "\n.text\ntarget: nop\n.local: nop\nemit: nop\n.eqv VALUE_LATE 4\n"
            withContext(Dispatchers.EDT) { fixture.addFileToProject("values.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun aliases(line: String, start: String = before): Set<String> {
                    fixture.configureByText("data-aliases.mars", start + line + after)
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().filter { item -> item.typeText() == "Equivalence" }
                            .map { item -> item.lookupString }.toSet()
                    }
                }
                val integers = setOf("VALUE_NUM", "VALUE_ZERO", "VALUE_NEG")
                val list = integers + setOf("VALUE_LIST", "VALUE_SYMBOL", "VALUE_DOTTED", "VALUE_MACRO")
                assertEquals(list, aliases(".word VALUE_<caret>"))
                assertEquals(list, aliases(".half VALUE_<caret>"))
                assertEquals(list, aliases(".byte VALUE_<caret>"))
                assertEquals(list, aliases("slot: WORDS VALUE_<caret>"))
                assertEquals(list - "VALUE_DOTTED", aliases(".word 0\nVALUE_<caret>"))
                assertEquals(setOf("WORDS"), aliases("WOR<caret>"))
                assertEquals(list, aliases(".word 0\n1, VALUE_<caret>"))
                assertEquals(integers, aliases(".word VALUE_<caret>:2"))
                assertEquals(setOf("VALUE_NUM"), aliases("WORDS 1:VALUE_<caret>"))
                assertEquals(integers, aliases(".word 0\n0, VALUE_<caret>:2"))
                assertEquals(integers, aliases(".word 0\n.5, VALUE_<caret>:2"))
                assertTrue(aliases($$".word 0\n$t0, VALUE_<caret>:2").isEmpty())
                assertTrue(aliases(".word VALUE_<caret>:0").isEmpty())
                assertTrue(aliases(".word VALUE_<caret>:1.5").isEmpty())
                assertTrue(aliases(".word target:VALUE_<caret>").isEmpty())
                assertTrue(aliases($$".word VALUE_<caret>, $t0").isEmpty())
                assertEquals(integers + setOf("VALUE_LIST", "VALUE_FLOAT", "VALUE_MACRO"), aliases(".float 0\nVALUE_<caret>"))
                assertTrue(aliases(".set VALUE_<caret>").isEmpty())
                assertTrue(aliases(".word VALUE_<caret>", "$include.text\n").isEmpty())
                val unindexed = fixture.configureByText("data-indexing.mars", "$before.word VALUE_$after") as MipsFile
                val range = unindexed.findElementAt(before.length + ".word ".length)!!.textRange
                DumbModeTestUtils.runInDumbModeSynchronously(current) {
                    assertTrue(current.service<MarsAnalysisService>()
                        .directiveOperandEquivalenceCompletions(unindexed, range, "VALUE_").isEmpty())
                }
                assertEquals(setOf("VALUE_LIST"), aliases("slot: WORDS 2, VALUE_L<caret>.old, 9"))
                val selected = fixture.lookup.items.single { it.lookupString == "VALUE_LIST" }
                assertEquals(" = VALUE_NUM, 8", LookupElementPresentation().also(selected::renderElement).tailText)
                fixture.lookup.currentItem = selected
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(before + "slot: WORDS 2, VALUE_LIST, 9" + after, fixture.file.text)
                fixture.file.text
            }
            directory.resolve("values.inc").writeText(definitions)
            val entry = directory.resolve("data-aliases.mars").also { it.writeText(completed) }
            val context = current.service<MarsSessionService>().context
            context.assemble(listOf(entry), null, extended = true, warnings = false)
            val memory = context.memory
            assertEquals(listOf(2, 3, 8, 9), List(4) { memory.getWordNoNotify(memory.dataBaseAddress + it * Int.SIZE_BYTES) })
        }

    @Test
    fun `data lists complete real and quoted equivalences using the active directive`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = ".eqv REALS .float\n.eqv STRINGS .asciiz\n.eqv VAL_INT 3\n.eqv VAL_REAL -1.5\n" +
                ".eqv VAL_MIX VAL_REAL, 2.25\n.eqv VAL_WIDE 1.0e40\n.eqv VAL_HEX 0x10\n" +
                $$".eqv VAL_REG $t0\n.eqv VAL_ZERO 0\n.eqv VAL_LABEL target\n" +
                ".eqv VAL_STRING \"A\\n\"\n.eqv VAL_QUOTES VAL_STRING, \"B\"\n.eqv VAL_OPEN \"unfinished\n"
            val include = ".include \"real-and-string.inc\"\n"
            val before = "$include.data\n"
            val after = "\n.text\ntarget: nop\n.eqv VAL_LATE 4.5\n"
            withContext(Dispatchers.EDT) { fixture.addFileToProject("real-and-string.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun aliases(line: String, start: String = before): Set<String> {
                    fixture.configureByText("real-and-string.mars", start + line + after)
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().filter { item -> item.typeText() == "Equivalence" }
                            .map { item -> item.lookupString }.toSet()
                    }
                }
                val reals = setOf("VAL_INT", "VAL_REAL", "VAL_MIX", "VAL_ZERO")
                val strings = setOf("VAL_STRING", "VAL_QUOTES")
                assertEquals(reals, aliases(".float VAL_<caret>"))
                assertEquals(reals + "VAL_WIDE", aliases(".double VAL_<caret>"))
                assertEquals(reals, aliases(".float 1\nVAL_<caret>"))
                assertEquals(reals, aliases(".kdata\n.float 1\n.text\n.kdata\nVAL_<caret>", include))
                assertEquals(reals - "VAL_MIX", aliases("REALS VAL_<caret>:2"))
                assertEquals(setOf("VAL_INT", "VAL_HEX"), aliases(".float -1.5:VAL_<caret>"))
                assertEquals(strings, aliases(".ascii VAL_<caret>"))
                assertEquals(strings, aliases(".asciiz \"x\"\nVAL_<caret>"))
                assertEquals(strings, aliases("label: STRINGS VAL_<caret>"))
                assertTrue(aliases(".ascii VAL_<caret>:2").isEmpty())
                assertTrue(aliases(".asciiz VAL_<caret>, \"unfinished").isEmpty())
                assertTrue(aliases(".ascii VAL_<caret>", "$include.text\n").isEmpty())
                assertEquals(setOf("VAL_QUOTES"), aliases(".float VAL_MIX\nlabel: STRINGS VAL_Q<caret>.old, \"Z\""))
                fixture.lookup.currentItem = fixture.lookup.items.single { it.lookupString == "VAL_QUOTES" }
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals("$before.float VAL_MIX\nlabel: STRINGS VAL_QUOTES, \"Z\"$after", fixture.file.text)
                fixture.file.text
            }
            directory.resolve("real-and-string.inc").writeText(definitions)
            val entry = directory.resolve("real-and-string.mars").also { it.writeText(completed) }
            val context = current.service<MarsSessionService>().context
            context.assemble(listOf(entry), null, extended = true, warnings = false)
            val memory = context.memory
            val base = memory.dataBaseAddress
            assertEquals((-1.5f).toBits(), memory.getWordNoNotify(base))
            assertEquals(2.25f.toBits(), memory.getWordNoNotify(base + Int.SIZE_BYTES))
            assertEquals(listOf(65, 10, 0, 66, 0, 90, 0), List(7) { memory.getByte(base + 2 * Int.SIZE_BYTES + it) })
        }

    @Test
    fun `directive operands complete addresses and nonnegative sizes in the assembler slots`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = ".eqv RESERVE .space\n.eqv STORAGE .extern first\n.eqv SELECT_TEXT .text\n" +
                ".eqv SIZE_OK 4\n.eqv SIZE_CHAIN SIZE_OK\n.eqv SIZE_ZERO 0\n.eqv SIZE_HEX 0x10\n" +
                ".eqv SIZE_NEG -4\n.eqv SIZE_REAL 4.0\n.eqv SIZE_MULTI 4, 8\n.eqv SIZE_NAME symbol\n" +
                ".eqv SIZE_ADDRESS 0x10010010\n"
            val include = ".include \"sizes.inc\"\n"
            withContext(Dispatchers.EDT) { fixture.addFileToProject("sizes.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun aliases(source: String): Set<String> {
                    fixture.configureByText("directive-sizes.mars", "$include$source\n.eqv SIZE_LATE 8\n")
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().filter { item -> item.typeText() == "Equivalence" }
                            .map { item -> item.lookupString }.toSet()
                    }
                }
                val sizes = setOf("SIZE_OK", "SIZE_CHAIN", "SIZE_ZERO", "SIZE_HEX", "SIZE_ADDRESS")
                val addresses = sizes + "SIZE_NEG"
                assertEquals(sizes, aliases(".data\n.space SIZE_<caret>"))
                assertEquals(sizes, aliases(".kdata\n.align SIZE_<caret>"))
                assertEquals(sizes, aliases(".data\nslot: RESERVE SIZE_<caret>"))
                assertEquals(sizes, aliases(".extern first SIZE_<caret>"))
                assertEquals(sizes, aliases("STORAGE SIZE_<caret>"))
                for (directive in listOf(".data", ".kdata", ".text", ".ktext", "SELECT_TEXT")) {
                    assertEquals(addresses, aliases("$directive SIZE_<caret> # trailing comment"), directive)
                }
                assertEquals(addresses, aliases(".text SIZE_<caret>, ignored"))
                assertTrue(aliases(".space SIZE_<caret>").isEmpty())
                assertTrue(aliases(".data\n.align SIZE_<caret> 1").isEmpty())
                assertTrue(aliases(".extern SIZE_<caret> 4").isEmpty())
                assertTrue(aliases(".extern first SIZE_<caret>, 4").isEmpty())
                assertTrue(aliases(".data 0x10010000 SIZE_<caret>").isEmpty())
                assertTrue(aliases(".extern 0x SIZE_<caret>").isEmpty())
                val source = ".data SIZE_ADDRESS\nslot: RESERVE SIZE_CH<caret>.old\nmarker: .word 23\n.text\nnop"
                assertEquals(setOf("SIZE_CHAIN"), aliases(source))
                fixture.lookup.currentItem = fixture.lookup.items.single { it.lookupString == "SIZE_CHAIN" }
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(include + source.replace("SIZE_CH<caret>.old", "SIZE_CHAIN") + "\n.eqv SIZE_LATE 8\n", fixture.file.text)
                fixture.file.text
            }
            directory.resolve("sizes.inc").writeText(definitions)
            val entry = directory.resolve("directive-sizes.mars").also { it.writeText(completed) }
            val context = current.service<MarsSessionService>().context
            context.assemble(listOf(entry), null, extended = true, warnings = false)
            assertEquals(23, context.memory.getWordNoNotify(context.memory.dataBaseAddress + 20))
        }

    @Test
    fun `export completion offers eligible labels from the current assembly root`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = ".eqv EXPORT .globl\n.eqv ALREADY exportIncluded\n" +
                ".eqv REST ALREADY, exportLocal\n.eqv LOOP AGAIN\n.eqv AGAIN LOOP\nexportIncluded: nop\n"
            val prefix = ".include \"exports.inc\"\n.extern exportExternal 4\n"
            val labels = "\nexportLocal: nop\nexportSecond: nop\nadd.s: nop\n"
            withContext(Dispatchers.EDT) {
                fixture.addFileToProject("exports.inc", definitions)
                fixture.addFileToProject("separate.mars", ".globl exportOther\nexportOther: nop\n")
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun candidates(line: String): Set<String> {
                    fixture.configureByText("export-completion.mars", prefix + line + labels)
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().map { item -> item.lookupString }.toSet()
                    }
                }
                val expected = setOf("exportIncluded", "exportLocal", "exportSecond")
                assertEquals(expected, candidates(".GlObL export<caret>"))
                assertEquals(expected, candidates("marker: EXPORT export<caret>"))
                assertEquals(expected - "exportIncluded", candidates(".globl ALREADY, export<caret>"))
                assertEquals(expected - "exportSecond", candidates(".globl export<caret>, exportSecond # existing"))
                assertEquals(expected - "exportIncluded", candidates("marker: EXPORT export<caret>.old, ALREADY"))
                assertEquals(expected - "exportLocal", candidates("EXPORT export<caret>, 0x, exportLocal"))
                assertEquals(expected - "exportIncluded", candidates("EXPORT ALREADY, export<caret>, LOOP"))
                assertEquals(expected, candidates("exportShadow: nop\n.eqv exportShadow 1\n.globl export<caret>"))
                assertTrue(candidates(".globl 1, export<caret>").isEmpty())
                assertTrue(candidates(".eqv FIRST SECOND\n.eqv SECOND FIRST\nEXPORT FIRST, export<caret>").isEmpty())
                assertTrue(candidates(".eqv LABEL marker:\nLABEL export<caret>").isEmpty())
                assertTrue("add.s" !in candidates(".globl ad<caret>"))
                val file = fixture.configureByText("export-indexing.mars", "$prefix.globl export<caret>$labels") as MipsFile
                val head = file.findElementAt(prefix.length)!!
                val operand = file.findElementAt(fixture.editor.caretModel.offset - 1)!!
                DumbModeTestUtils.runInDumbModeSynchronously(current) {
                    assertNull(current.service<MarsAnalysisService>().exportOperandsBefore(file, head, operand))
                }
                assertEquals(setOf("exportSecond"), candidates("EXPORT export<caret>.old, REST"))
                fixture.lookup.currentItem = fixture.lookup.items.single { it.lookupString == "exportSecond" }
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(prefix + "EXPORT exportSecond, REST" + labels, fixture.file.text)
                fixture.file.text
            }
            directory.resolve("exports.inc").writeText(definitions)
            val entry = directory.resolve("export-completion.mars").also { it.writeText(completed) }
            val context = current.service<MarsSessionService>().context
            context.assemble(listOf(entry), null, extended = true, warnings = false)
            assertEquals(setOf("exportExternal", "exportIncluded", "exportLocal", "exportSecond"),
                context.symbolTable.allSymbols.map { it.name }.toSet())
        }

    @Test
    fun `export completion offers aliases whose expanded labels are local and distinct`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = ".eqv EXPORT .globl\n.eqv EX_FIRST alpha\n.eqv EX_SECOND beta\n" +
                ".eqv EX_PAIR EX_FIRST, beta\n.eqv EX_CHAIN EX_PAIR\n.eqv EX_DUP alpha, alpha\n" +
                ".eqv EX_EXTERNAL storage\n.eqv EX_MISSING absent\n.eqv EX_OTHER other\n" +
                $$".eqv EX_OPCODE add.s\n.eqv EX_REGISTER $t0\n.eqv LOOP AGAIN\n.eqv AGAIN LOOP\n" +
                ".eqv EX_INCLUDED included\n.eqv MADE generated:\n.eqv EX_GENERATED generated\nincluded: nop\n"
            val prefix = ".include \"export-aliases.inc\"\n.extern storage 4\n"
            val suffix = "\nalpha: nop\nbeta: nop\ngamma: nop\nadd.s: nop\nMADE nop\n.eqv EX_LATE gamma\n"
            withContext(Dispatchers.EDT) {
                fixture.addFileToProject("export-aliases.inc", definitions)
                fixture.addFileToProject("separate.mars", ".globl other\nother: nop\n")
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun aliases(line: String): Set<String> {
                    fixture.configureByText("export-aliases.mars", prefix + line + suffix)
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().filter { item -> item.typeText() == "Equivalence" }
                            .map { it.lookupString }.toSet()
                    }
                }
                val independent = setOf("EX_INCLUDED", "EX_GENERATED")
                assertEquals(independent + setOf("EX_FIRST", "EX_SECOND", "EX_PAIR", "EX_CHAIN"), aliases(".globl EX_<caret>"))
                assertEquals(independent + "EX_FIRST", aliases("tag: EXPORT EX_<caret>, beta"))
                assertEquals(independent + "EX_SECOND", aliases(".globl EX_FIRST, EX_<caret>"))
                assertEquals(independent, aliases(".globl alpha, EX_<caret>, EX_SECOND"))
                assertTrue(aliases($$".globl EX_<caret>, $t0").isEmpty())
                assertTrue(aliases(".globl EX_<caret>, LOOP").isEmpty())
                assertEquals(setOf("EX_PAIR"), aliases("EXPORT EX_P<caret>.old, gamma"))
                fixture.lookup.currentItem = fixture.lookup.items.single { it.lookupString == "EX_PAIR" }
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(prefix + "EXPORT EX_PAIR, gamma" + suffix, fixture.file.text)
                fixture.file.text
            }
            directory.resolve("export-aliases.inc").writeText(definitions)
            val entry = directory.resolve("export-aliases.mars").also { it.writeText(completed) }
            val context = current.service<MarsSessionService>().context
            context.assemble(listOf(entry), null, extended = true, warnings = false)
            assertEquals(setOf("storage", "alpha", "beta", "gamma"), context.symbolTable.allSymbols.map { it.name }.toSet())
        }

    @Test
    fun `instruction completion offers equivalences that fit the assembler operand format`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = $$"""
                .eqv ARG_REG $t1
                .eqv ARG_NUMBERED $9
                .eqv ARG_FP $f1
                .eqv ARG_SMALL 4
                .eqv ARG_LARGE 32
                .eqv ARG_ADDRESS ARG_SMALL($sp)
                .eqv ARG_PAIR $t1, 4
                .eqv ARG_TARGET target
                .eqv ARG_CYCLE LOOP
                .eqv LOOP ARG_CYCLE
                .eqv ARG_LABEL :
                .eqv ARG_REHEAD : add $t0
            """.trimIndent() + "\n"
            val include = ".include \"operands.inc\"\n"
            val suffix = "\ntarget: nop\n.eqv ARG_LATE 2\n"
            withContext(Dispatchers.EDT) { fixture.addFileToProject("operands.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun aliases(line: String): Set<String> {
                    fixture.configureByText("equivalence-completion.mars", include + line + suffix)
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().filter { item -> item.typeText() == "Equivalence" }
                            .map { item -> item.lookupString }.filter { name -> name.startsWith("ARG_") }.toSet()
                    }
                }
                assertEquals(setOf("ARG_SMALL"), aliases($$"sll $t0, $t1, ARG_<caret>"))
                assertEquals(setOf("ARG_REG", "ARG_NUMBERED"), aliases("sll ARG_<caret>"))
                assertEquals(setOf("ARG_REG", "ARG_NUMBERED", "ARG_PAIR"), aliases($$"sll $t0, ARG_<caret>"))
                assertEquals(setOf("ARG_ADDRESS", "ARG_SMALL", "ARG_LARGE", "ARG_TARGET"), aliases($$"lw $t0, ARG_<caret>"))
                assertEquals(setOf("ARG_REG", "ARG_NUMBERED"), aliases($$"lw $t0, 4(ARG_<caret>)"))
                assertEquals(setOf("ARG_FP"), aliases($$"add.s $f0, ARG_<caret>"))
                assertEquals(setOf("ARG_TARGET"), aliases("j ARG_<caret>"))
                assertTrue(aliases("nop ARG_<caret>").isEmpty())
                val file = fixture.configureByText("equivalence-indexing.mars", $$"sll $t0, ARG_<caret>") as MipsFile
                val position = file.findElementAt(fixture.editor.caretModel.offset - 1)!!
                DumbModeTestUtils.runInDumbModeSynchronously(current) {
                    val operand = requireNotNull(MipsInstructionOperand.at(position, file))
                    assertTrue(operand.equivalenceCompletions(file, "ARG_", bareMachine = false,
                        replacementEnd = position.textRange.endOffset).isEmpty())
                    assertTrue(current.service<MarsAnalysisService>()
                        .labelCompletionNames(file, position, "ARG_", LabelCompletionContext.INSTRUCTION).isEmpty())
                }
                val settings = current.service<MarsAssemblySettings>()
                settings.changeBareMachine(true)
                assertEquals(setOf("ARG_NUMBERED"), aliases($$"sll $8, ARG_<caret>"))
                settings.changeBareMachine(false)

                assertEquals(setOf("ARG_REG", "ARG_NUMBERED", "ARG_PAIR"), aliases($$"sll $t0, ARG_<caret>.old"))
                val pair = fixture.lookup.items.single { it.lookupString == "ARG_PAIR" }
                assertEquals($$" = $t1, 4", LookupElementPresentation().also(pair::renderElement).tailText)
                fixture.lookup.currentItem = pair
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(include + $$"sll $t0, ARG_PAIR" + suffix, fixture.file.text)
                fixture.file.text
            }
            directory.resolve("operands.inc").writeText(definitions)
            val entry = directory.resolve("equivalence-completion.mars").also { it.writeText(completed) }
            val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, extended = true, warnings = false)
            assertEquals(listOf(0x00094100, 0), program.machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `equivalence suggestions preserve already written operands and address punctuation`(@TempDir directory: Path) =
        timeoutRunBlocking {
            val current = project.get()
            val definitions = $$".eqv TAIL_BASE $sp\n.eqv TAIL_CLOSE $sp)\n.eqv TAIL_REG $t1\n" +
                $$".eqv TAIL_PAIR $t1, 4\n.eqv TAIL_ADDR 4($sp)\n.eqv TAIL_OFFSET 4\n.eqv CLOSE )\n" +
                ".eqv LOOP AGAIN\n.eqv AGAIN LOOP\n"
            val include = ".include \"suffix.inc\"\n"
            withContext(Dispatchers.EDT) { fixture.addFileToProject("suffix.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun aliases(line: String, newline: String = "\n"): Set<String> {
                    fixture.configureByText("suffix-completion.mars", include + line + newline)
                    return CodeInsightSettings.runWithTemporarySettings<_, Error> {
                        it.AUTOCOMPLETE_ON_CODE_COMPLETION = false
                        fixture.completeBasic().orEmpty().map { item -> item.lookupString }.filter { it.startsWith("TAIL_") }.toSet()
                    }
                }
                val registers = setOf("TAIL_BASE", "TAIL_REG")
                assertEquals(registers, aliases($$"lw $t0, 4(TAIL_<caret>)"))
                assertEquals(registers, aliases($$"lw $t0, 4(TAIL_<caret> CLOSE"))
                assertEquals(registers, aliases($$"lw $t0, 4(<caret>)"))
                assertEquals(registers + "TAIL_CLOSE", aliases($$"lw $t0, 4(TAIL_<caret>", newline = ""))
                assertEquals(registers + "TAIL_CLOSE", aliases($$"lw $t0, 4(<caret>", newline = ""))
                assertEquals(setOf("TAIL_OFFSET"), aliases($$"lw $t0, TAIL_<caret>($sp)"))
                assertEquals(registers, aliases($$"sll $t0, TAIL_<caret>, 7"))
                assertEquals(registers, aliases($$"sll $t0, TAIL_<caret>, 0x"))
                assertTrue(aliases($$"sll $t0, TAIL_<caret>, )").isEmpty())
                assertEquals(registers + "TAIL_PAIR", aliases($$"sll $t0, TAIL_<caret>, LOOP"))
                assertEquals(registers, aliases($$"lw $t0, 4(TAIL_<caret>.old) # keep the suffix"))
                fixture.lookup.currentItem = fixture.lookup.items.single { it.lookupString == "TAIL_BASE" }
                fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                assertEquals(include + $$"lw $t0, 4(TAIL_BASE) # keep the suffix\n", fixture.file.text)
                fixture.file.text
            }
            directory.resolve("suffix.inc").writeText(definitions)
            val entry = directory.resolve("suffix-completion.mars").also { it.writeText(completed) }
            val program = current.service<MarsSessionService>().context.assemble(listOf(entry), null, extended = true, warnings = false)
            assertEquals(listOf(0x8fa80004.toInt()), program.machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `register insertion replaces the complete token and preserves the address punctuation`() = timeoutRunBlocking {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.configureByText("registers.asm", $$"lw $t0, 4($t<caret>8)\n")
            val item = fixture.completeBasic().orEmpty().single { it.lookupString == $$"$t2" }
            fixture.lookup.currentItem = item
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals($$"lw $t0, 4($t2)\n", fixture.file.text)
        }
    }

    @Test
    // Kotlin exposes the reported Java setters as read-only properties.
    @Suppress("UsePropertyAccessSyntax")
    fun `label completion follows current and included files and replaces a dotted target`() = timeoutRunBlocking {
        val current = project.get()
        val included = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/inner.s", "Target.nested: nop\n.include \"outer.s\"\n")
            fixture.addFileToProject("parts/plain.txt", "Target.plain: nop\n.eqv Target.renamed Target.effective\nTarget.renamed: nop\n")
            fixture.addFileToProject("unrelated.asm", ".globl Target.unrelated\nTarget.unrelated: nop\n")
            fixture.addFileToProject("parts/outer.s", "Target.included: nop\n.include \"inner.s\"\n")
        }
        withContext(Dispatchers.EDT) {
            fixture.configureByText("labels.asm", $$"""
                .include "parts/outer.s"
                .include "parts/plain.txt"
                .include "missing.s"
                .eqv Target.constant 2
                .macro Target.macro
                  Target.private: nop
                .end_macro
                entry: beq $t0, $zero, Target.<caret>old
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
            assertEquals(setOf("Target.renamed"), matches.filter { it.typeText() == "Equivalence" }.map { it.lookupString }.toSet())
            assertEquals(setOf("Target.local", "Target.nested", "Target.updated", "Target.plain", "Target.effective"),
                matches.filterNot { it.typeText() == "Equivalence" }.map { it.lookupString }.toSet())
            assertTrue("target.lowercase" !in fixture.lookupElementStrings.orEmpty())
            fixture.lookup.currentItem = matches.single { it.lookupString == "Target.updated" }
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertTrue(fixture.file.text.contains($$"entry: beq $t0, $zero, Target.updated\n"))
        }
    }

    @Test
    fun `labels inside macros stay in their physical macro scope`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("macro-part.s", $$"injectedLabel: nop\n$includedParameterLabel: nop\n")
            fixture.addFileToProject("macro-extra.txt",
              $$"plainLabel: nop\n%generatedLabel: nop\n$plainParameterLabel: nop\n"
            )
            fixture.addFileToProject("other-part.s", "hiddenLabel: nop\n")
            fixture.configureByText("macro.asm", $$"""
                outerLabel: nop
                $gccLabel: nop
                .macro other
                  .include "other-part.s"
                  otherLabel: nop
                .end_macro
                .macro emit ($parameterLabel, $includedParameterLabel, $plainParameterLabel, %generatedLabel)
                  .include "macro-part.s"
                  .include "macro-extra.txt"
                  currentLabel: nop
                  $parameterLabel: nop
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
            assertEquals(setOf("outerLabel", $$"$gccLabel", "currentLabel", "injectedLabel", "plainLabel"),
                labels.map { it.lookupString }.toSet())
            assertEquals(setOf($$"$includedParameterLabel", $$"$parameterLabel", $$"$plainParameterLabel", "%generatedLabel"),
                parameters.map { it.lookupString }.toSet())
        }
    }

    @Test
    fun `indexed label completion resumes when indexing finishes`() = timeoutRunBlocking {
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
    fun `completion exposes expanded labels and hides the replaced spelling`() = timeoutRunBlocking {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("expanded-labels.asm", $$"""
                .eqv PREFIX labelGenerated:
                .eqv labelOld labelRenamed
                .eqv PRIVATE labelPrivate:
                .eqv FORMAL $labelParameter:
                .eqv OTHER unrelated:
                .eqv SEP :
                PREFIX nop
                labelSeparated SEP nop
                OTHER nop
                labelOld: nop
                labelShadow: nop
                .eqv labelShadow 7
                .macro other
                  PRIVATE nop
                  FORMAL nop
                .end_macro
                j label<caret>
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val items = fixture.completeBasic().orEmpty().filter { it.lookupString.startsWith("label") }
            assertEquals(setOf("labelOld"), items.filter { it.typeText() == "Equivalence" }.map { it.lookupString }.toSet())
            assertEquals(setOf("labelGenerated", "labelRenamed", "labelSeparated"),
                items.filterNot { it.typeText() == "Equivalence" }.map { it.lookupString }.toSet())
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
    fun `catalog heads do not complete in comments strings operands or declaration names`() = timeoutRunBlocking {
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

    private fun LookupElement.typeText(): String? = LookupElementPresentation().also(::renderElement).typeText
}
