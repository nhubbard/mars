package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.options.colors.ColorSettingsPages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.TokenType
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.build.MarsBuildService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@TestApplication
class MipsEditorPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `assembly editors highlight mnemonics only in instruction positions`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val source = """
                .text
                addiu: addiu ${'$'}t0, ${'$'}zero, 1
                    LI ${'$'}v0, 10
                    c.eq.s ${'$'}f0, ${'$'}f2
                    s.s ${'$'}f0, addiu
                    # syscall
                    syscall
            """.trimIndent()
            for (extension in listOf("asm", "s")) {
                val file = fixture.addFileToProject("highlight.$extension", source)
                fixture.configureFromExistingVirtualFile(file.virtualFile)
                assertSame(MipsFileType, fixture.file.fileType)
                val editor = fixture.editor
                val highlights = fixture.doHighlighting().filter { it.forcedTextAttributesKey == instructionMnemonicColor }
                val expectedSpans = listOf("addiu", "LI", "c.eq.s", "s.s").map { mnemonic ->
                    val start = source.indexOf(" $mnemonic ") + 1
                    start until (start + mnemonic.length)
                } + listOf(source.lastIndexOf("syscall") until source.length)
                assertEquals(
                    expectedSpans,
                    highlights.sortedBy { it.startOffset }.map { it.startOffset until it.endOffset },
                    extension,
                )
                val expected = editor.colorsScheme.getAttributes(instructionMnemonicColor)
                val label = editor.highlighter.createIterator(source.indexOf("addiu:")).textAttributes
                assertNotEquals(label, expected)
                for (highlight in highlights) {
                    assertEquals(expected, highlight.getTextAttributes(fixture.file, editor.colorsScheme))
                }
                val register = editor.highlighter.createIterator(source.indexOf("${'$'}t0")).textAttributes
                assertEquals(
                    editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INSTANCE_FIELD).foregroundColor,
                    register.foregroundColor,
                )
            }
        }
    }

    @Test
    fun `only aliases supplying an instruction are colored as mnemonics`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("aliases.s", ".eqv DEFINE .eqv\nDEFINE BRANCH bne \$t0, \$zero\n")
            fixture.configureByText("alias-highlight.asm", """
                .include "aliases.s"
                .eqv DATA_SEG .data
                .eqv FIRST SECOND
                .eqv SECOND FIRST
                .eqv LABEL row:
                .eqv OTHER there:
                .eqv BOTH next: addiu
                .eqv COLON :
                entry: BRANCH target
                DATA_SEG
                FIRST
                LABEL
                OTHER addiu ${'$'}t0, ${'$'}zero, 7
                split COLON BRANCH target
                BOTH ${'$'}t0, ${'$'}t0, 1
                addiu COLON syscall
                j COLON
                BRANCH: nop
                target: nop
                LATE target
                .eqv LATE j
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val file = fixture.file as MipsFile
            val source = file.text
            val highlights = fixture.doHighlighting().filter { it.forcedTextAttributesKey == instructionMnemonicColor }
            val expected = listOf(
                "BRANCH target" to "BRANCH", "addiu \$t0, \$zero, 7" to "addiu",
                "BRANCH target\nBOTH" to "BRANCH", "BOTH \$t0, \$t0, 1" to "BOTH",
                "syscall\nj COLON" to "syscall",
                "nop\ntarget" to "nop", "nop\nLATE" to "nop",
            ).map { (context, mnemonic) ->
                val start = source.indexOf(context)
                start until (start + mnemonic.length)
            }
            assertEquals(expected, highlights.sortedBy { it.startOffset }.map { it.startOffset until it.endOffset })
            val head = file.findElementAt(source.indexOf("BRANCH target"))!!
            val analysis = current.service<MarsAnalysisService>()
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertNull(analysis.expandedInstructionAlias(file, head, head.text))
                assertNull(analysis.operationSite(file, head))
                val literal = file.findElementAt(source.indexOf("addiu COLON"))!!
                assertFalse(analysis.isGeneratedLabel(file, literal))
                assertEquals(literal.textRange, analysis.operationSite(file, literal)?.range)
                assertEquals("addiu", analysis.editorInstructionTokens(file, literal, literal.text)?.get(0)?.value)
            }
        }
    }

    @Test
    fun `labels macros and equivalences use semantic colors in assembly editors`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("definitions.s", """
                .macro paint (%value)
                    li ${'$'}t0, %value
                .end_macro
            """.trimIndent())
            fixture.configureByText("symbols.asm", """
                .include "definitions.s"
                .eqv COUNT 7
                .eqv MARKER next:
                .eqv CALL paint
                .eqv DATA .data
                DATA
                .extern buffer 4
                table: .word buffer
                .text
                .globl main
                main: CALL (COUNT)
                MARKER paint (COUNT)
                addiu ${'$'}t0, ${'$'}t0, 1
                bgtz ${'$'}t0, next
                j missing
                local (2)
                .macro local (${'$'}parameter)
                    paint (${'$'}parameter)
                .end_macro
                .macro target (%where)
                %where: nop
                .end_macro
                local (5)
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val source = fixture.file.text
            val highlights = fixture.doHighlighting()
            fun assertColor(context: String, key: TextAttributesKey, word: String = context.substringBefore(' ')) {
                val start = source.indexOf(context) + context.indexOf(word)
                assertTrue(start >= 0 && highlights.any {
                    it.startOffset == start && it.endOffset == start + word.length && it.forcedTextAttributesKey == key
                }, context)
            }
            assertColor("buffer 4", labelColor)
            assertColor(".word buffer", labelColor, "buffer")
            assertColor("table:", labelColor, "table")
            assertColor(".globl main", labelColor, "main")
            assertColor("main: CALL", labelColor, "main")
            assertColor("MARKER paint", labelColor)
            assertColor("bgtz ${'$'}t0, next", labelColor, "next")
            assertColor("j missing", labelColor, "missing")
            assertColor(".macro local", macroColor, "local")
            assertColor("CALL (COUNT)", macroColor)
            assertColor("paint (COUNT)", macroColor)
            assertColor("paint (${'$'}parameter)", macroColor)
            assertColor("local (5)", macroColor)
            assertTrue(highlights.none { it.startOffset == source.indexOf("local (2)") && it.forcedTextAttributesKey == macroColor })
            assertColor("addiu ${'$'}t0", instructionMnemonicColor)
            assertColor(".eqv COUNT", equivalenceColor, "COUNT")
            assertColor("CALL (COUNT)", DefaultLanguageHighlighterColors.NUMBER, "COUNT")
            assertColor("(${'$'}parameter)\n", macroParameterColor, "${'$'}parameter")
            assertColor("paint (${'$'}parameter)", macroParameterColor, "${'$'}parameter")
            assertColor("DATA\n.extern", DefaultLanguageHighlighterColors.KEYWORD, "DATA")
        }
    }

    @Test
    fun `aliased macro headers color their written name and formals`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = """
            .eqv OPEN .macro
            .eqv CLOSE .end_macro
            .eqv NAME emit
            .eqv FORMALS (%reg, %value)
            .eqv BOTH blended (%arg)
            .eqv COMPLETE .macro whole (%arg)
            OPEN NAME FORMALS
              addiu %reg, ${'$'}zero, %value
            CLOSE
            OPEN simple (%arg)
              nop
            CLOSE
            OPEN BOTH
              nop
            CLOSE
            COMPLETE
              nop
            CLOSE
            emit (${'$'}t0, 7)
            simple (2)
            blended (3)
            whole (4)
        """.trimIndent()
        withContext(Dispatchers.EDT) { fixture.configureByText("alias-headers.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val file = fixture.file as MipsFile
            val highlights = fixture.doHighlighting()
            fun color(context: String, word: String): TextAttributesKey? {
                val start = source.indexOf(context) + context.indexOf(word)
                return highlights.singleOrNull {
                    it.startOffset == start && it.endOffset == start + word.length && it.forcedTextAttributesKey != null
                }?.forcedTextAttributesKey
            }
            assertEquals(DefaultLanguageHighlighterColors.KEYWORD, color("OPEN NAME FORMALS", "OPEN"))
            assertEquals(macroColor, color("OPEN NAME FORMALS", "NAME"))
            assertEquals(macroParameterColor, color("OPEN NAME FORMALS", "FORMALS"))
            assertEquals(macroColor, color("OPEN simple", "simple"))
            assertEquals(macroParameterColor, color("simple (%arg)", "%arg"))
            assertEquals(macroColor, color("OPEN BOTH", "BOTH"))
            assertEquals(DefaultLanguageHighlighterColors.KEYWORD, color("\nCOMPLETE\n", "COMPLETE"))
            for (call in listOf("emit (", "simple (2)", "blended (3)", "whole (4)")) {
                assertEquals(macroColor, color(call, call.substringBefore(' ')), call)
            }
            val name = file.findElementAt(source.indexOf("NAME FORMALS"))!!
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertNull(current.service<MarsAnalysisService>().generatedMacroPart(file, name))
            }
        }
    }

    @Test
    fun `macro arguments and aliases take the colors of their use sites`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("alias-colors.asm", """
                .eqv NUMBER -4
                .eqv BASE ${'$'}sp
                .eqv OPEN (
                .eqv CLOSE )
                .eqv DEST loop
                .eqv MESSAGE "hello"
                .eqv MANY 1, 2
                .eqv VALUE %value
                .eqv FIRST SECOND
                .eqv SECOND FIRST
                .eqv CALL emit
                .eqv OPCODE nop
                .macro emit (%value)
                    li ${'$'}t0, VALUE
                .end_macro
                .macro apply (%op)
                    %op
                .end_macro
                .data
                text: .asciiz MESSAGE
                numbers: .word MANY
                .data DEST
                .text
                loop: nop
                lw ${'$'}t1, NUMBER OPEN BASE CLOSE
                j DEST
                CALL (bare_argument)
                CALL (DEST)
                CALL (NUMBER)
                apply (OPCODE)
                CALL (FIRST)
                unknown (bare_argument)
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val source = fixture.file.text
            val highlights = fixture.doHighlighting()
            fun color(context: String, token: String): TextAttributesKey? {
                val start = source.indexOf(context) + context.indexOf(token)
                return highlights.singleOrNull { it.startOffset == start && it.endOffset == start + token.length &&
                    it.forcedTextAttributesKey != null }?.forcedTextAttributesKey
            }
            assertEquals(DefaultLanguageHighlighterColors.NUMBER, color("NUMBER OPEN", "NUMBER"))
            assertEquals(DefaultLanguageHighlighterColors.INSTANCE_FIELD, color("BASE CLOSE", "BASE"))
            assertEquals(punctuationColor, color("OPEN BASE", "OPEN"))
            assertEquals(punctuationColor, color("BASE CLOSE", "CLOSE"))
            assertEquals(labelColor, color("j DEST", "DEST"))
            assertEquals(DefaultLanguageHighlighterColors.STRING, color(".asciiz MESSAGE", "MESSAGE"))
            assertEquals(macroParameterColor, color("li \$t0, VALUE", "VALUE"))
            assertEquals(equivalenceColor, color(".word MANY", "MANY"))
            assertEquals(macroColor, color("CALL (bare_argument)", "CALL"))
            assertEquals(macroParameterColor, color("CALL (bare_argument)", "bare_argument"))
            assertEquals(macroParameterColor, color("CALL (DEST)", "DEST"))
            assertEquals(equivalenceColor, color(".data DEST", "DEST"))
            assertEquals(DefaultLanguageHighlighterColors.NUMBER, color("CALL (NUMBER)", "NUMBER"))
            assertEquals(macroParameterColor, color("apply (OPCODE)", "OPCODE"))
            val cycleStart = source.lastIndexOf("FIRST")
            assertTrue(highlights.any {
                it.startOffset == cycleStart && it.endOffset == cycleStart + "FIRST".length &&
                    (it.forcedTextAttributesKey == equivalenceColor ||
                        it.description?.contains("Circular .eqv expansion") == true)
            }, highlights.filter { it.startOffset == cycleStart }.joinToString())
            assertNull(color("unknown (bare_argument)", "bare_argument"))
        }
    }

    @Test
    fun `signed literals punctuation macro formals and equivalence replacements get visible styles`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.configureByText("punctuation.asm", """
                .eqv DISP -4
                .eqv NEXT repeat_loop
                .eqv INCREMENT addiu
                .macro bump (%counter)
                    addiu %counter, %counter, -1
                .end_macro
                repeat_loop: bump (${'$'}t0)
                lw ${'$'}t1, -4(${'$'}t0)
                la ${'$'}t0, repeat_loop+4
                .word - 5
                -
            """.trimIndent())
            val source = fixture.file.text
            val highlights = fixture.doHighlighting()
            fun hasHighlight(context: String, token: String, key: TextAttributesKey): Boolean {
                val start = source.indexOf(context) + context.indexOf(token)
                return highlights.any {
                    it.startOffset == start && it.endOffset == start + token.length && it.forcedTextAttributesKey == key
                }
            }
            for (context in listOf("DISP -4", "%counter, -1", "-4(${'$'}t0)")) {
                assertTrue(hasHighlight(context, "-", DefaultLanguageHighlighterColors.NUMBER), context)
            }
            assertFalse(hasHighlight("repeat_loop+4", "+", DefaultLanguageHighlighterColors.NUMBER))
            assertFalse(hasHighlight(".word - 5", "-", DefaultLanguageHighlighterColors.NUMBER))
            assertTrue(hasHighlight("NEXT repeat_loop", "repeat_loop", equivalenceReplacementColor))
            assertTrue(hasHighlight("INCREMENT addiu", "addiu", equivalenceReplacementColor))
            val editor = fixture.editor
            val scheme = editor.colorsScheme
            val punctuation = scheme.getAttributes(punctuationColor).foregroundColor
            val parameters = scheme.getAttributes(macroParameterColor).foregroundColor
            for (offset in source.indices.filter { source[it] in ":()" }) {
                assertEquals(punctuation, editor.highlighter.createIterator(offset).textAttributes.foregroundColor)
            }
            val parameterOffset = source.indexOf("%counter")
            assertEquals(parameters, editor.highlighter.createIterator(parameterOffset).textAttributes.foregroundColor)
        }
    }

    @Test
    fun `all significant lexer tokens have an editor color category`() {
        val syntax = MipsSyntaxHighlighter()
        val source = ".text .macro .end_macro .eqv .include .set .extern .word foo addiu \$macro \$t0 %p 7 \"ok\" 'a' : , ( ) + - # note\n\"incomplete\n'bad\n"
        val lexer = syntax.highlightingLexer
        lexer.start(source)
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            if (type != TokenType.WHITE_SPACE && type != MipsTypes.psi("NEWLINE")) {
                assertTrue(syntax.getTokenHighlights(type).isNotEmpty(), type.toString())
            }
            lexer.advance()
        }
    }

    @Test
    fun `color settings expose previews and theme-aware defaults`() {
        val page = ColorSettingsPages.getInstance().registeredPages.filterIsInstance<MipsColorSettingsPage>().single()
        assertEquals("MARS MIPS", page.displayName)
        assertSame(MipsFileType.icon, page.icon)
        assertInstanceOf(MipsSyntaxHighlighter::class.java, page.highlighter)
        assertTrue(page.colorDescriptors.isEmpty())
        assertEquals(
            mapOf("Instruction mnemonic" to instructionMnemonicColor, "Label" to labelColor,
                "Macro name" to macroColor, "Equivalence" to equivalenceColor,
                "Equivalence replacement symbol" to equivalenceReplacementColor, "Macro parameter" to macroParameterColor,
                "Punctuation and operators" to punctuationColor),
            page.attributeDescriptors.associate { it.displayName to it.key },
        )
        assertEquals(
            mapOf("instruction" to instructionMnemonicColor, "label" to labelColor, "macro" to macroColor,
                "equivalence" to equivalenceColor, "replacement" to equivalenceReplacementColor,
                "parameter" to macroParameterColor, "punctuation" to punctuationColor),
            page.additionalHighlightingTagToDescriptorMap,
        )
        assertTrue(page.demoText.contains("<instruction>li</instruction>"))
        assertTrue(page.demoText.contains("<label>loop</label>"))
        assertTrue(page.demoText.contains("<macro>decrement</macro>"))
        assertSame(DefaultLanguageHighlighterColors.KEYWORD, instructionMnemonicColor.fallbackAttributeKey)
        assertEquals("KOTLIN_LABEL", labelColor.fallbackAttributeKey?.externalName)
        assertEquals("KOTLIN_FUNCTION_DECLARATION", macroColor.fallbackAttributeKey?.externalName)

        val colors = EditorColorsManager.getInstance()
        for ((name, accent) in mapOf("Default" to 0x0033B3, "Darcula" to 0xCF8E6D)) {
            val scheme = checkNotNull(colors.getScheme(name)) { name }
            val mnemonic = scheme.getAttributes(instructionMnemonicColor)
            assertEquals(Color(accent), mnemonic.foregroundColor, name)
            assertEquals(Font.BOLD, mnemonic.fontType, name)
            assertNotEquals(scheme.getAttributes(HighlighterColors.TEXT).foregroundColor, mnemonic.foregroundColor, name)
            val text = scheme.getAttributes(HighlighterColors.TEXT).foregroundColor
            assertNotEquals(text, scheme.getAttributes(punctuationColor).foregroundColor, name)
            assertNotEquals(text, scheme.getAttributes(macroParameterColor).foregroundColor, name)
        }
    }

    @Test
    fun `platform comment action toggles a MARS line without changing string hashes`() = timeoutRunBlocking<Unit> {
        // The editor fixture and its actions require model access on the UI thread.
        withContext(Dispatchers.EDT) {
            fixture.configureByText(MipsFileType, ".asciiz \"# message\"<caret>\n")
            fixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
            assertEquals("#.asciiz \"# message\"\n", fixture.editor.document.text)
            fixture.editor.caretModel.moveToOffset(0)
            fixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
            fixture.checkResult(".asciiz \"# message\"\n")
        }
    }

    @Test
    fun `platform comment action toggles selected instructions and existing comments`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val source = "<selection>li \$v0, 1\n# explain\nsyscall</selection>\n"
            fixture.configureByText(MipsFileType, source)
            fixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
            assertEquals("#li \$v0, 1\n## explain\n#syscall\n", fixture.editor.document.text)
            fixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
            fixture.checkResult("li \$v0, 1\n# explain\nsyscall\n")
        }
    }

    @Test
    fun `platform typing pairs parentheses skips an existing close and deletes an empty pair`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.configureByText(MipsFileType, "lw \$t0, 4<caret>")
            fixture.type('(')
            fixture.checkResult("lw \$t0, 4(<caret>)")
            fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
            fixture.checkResult("lw \$t0, 4<caret>")
            fixture.type("(\$sp)")
            fixture.checkResult("lw \$t0, 4(\$sp)<caret>")
        }
    }

    @Test
    fun `platform quote typing pairs skips and deletes literal delimiters`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            for ((directive, quote, content) in listOf(
                Triple(".include ", '"', "part.s"),
                Triple(".byte ", '\'', "A"),
            )) {
                fixture.configureByText(MipsFileType, "$directive<caret>\nnext: nop")
                fixture.type(quote)
                fixture.checkResult("$directive$quote<caret>$quote\nnext: nop")
                fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
                fixture.checkResult("$directive<caret>\nnext: nop")
                fixture.type("$quote$content$quote")
                fixture.checkResult("$directive$quote$content$quote<caret>\nnext: nop")
            }
            fixture.configureByText(MipsFileType, ".asciiz <caret>\"")
            fixture.type('"')
            fixture.checkResult(".asciiz \"<caret>\"")
        }
    }

    @Test
    fun `platform quote typing preserves escaped delimiters and comments`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.configureByText(MipsFileType, ".byte '\\<caret>'")
            fixture.type('\'')
            fixture.checkResult(".byte '\\'<caret>'")

            fixture.configureByText(MipsFileType, ".asciiz \"a\\<caret>\"")
            fixture.type('"')
            fixture.checkResult(".asciiz \"a\\\"<caret>\"")

            fixture.configureByText(MipsFileType, """.asciiz "a\\<caret>" # tail""")
            fixture.type('"')
            fixture.checkResult(""".asciiz "a\\"<caret>" # tail""")

            fixture.configureByText(MipsFileType, "# <caret>")
            fixture.type("\"'`")
            fixture.checkResult("# \"'`<caret>")
        }
    }

    @Test
    fun `native folding hides only the macro body and leaves its header and terminator visible`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val header = "macro_label: .MaCrO emit (%value) # signature"
            val body = "  # body comment\n  addiu \$t0, \$zero, %value"
            val source = "$header\n$body\n.EnD_mAcRo,, # terminator\nnext: nop\n"
            for (extension in listOf("asm", "s")) {
                fixture.configureByText("fold.$extension", source)
                val editor = fixture.editor
                EditorTestUtil.buildInitialFoldingsInBackground(editor)
                val fold = editor.foldingModel.allFoldRegions.single()
                assertTrue(fold.isExpanded)
                assertEquals(header.length, fold.startOffset)
                assertEquals(source.indexOf("\n.EnD_mAcRo"), fold.endOffset)
                assertEquals("\n$body", source.substring(fold.startOffset, fold.endOffset))
                assertEquals(" …", fold.placeholderText)
                editor.caretModel.moveToOffset(source.indexOf("emit"))
                fixture.performEditorAction(IdeActions.ACTION_COLLAPSE_REGION)
                assertFalse(fold.isExpanded)
                fixture.performEditorAction(IdeActions.ACTION_EXPAND_REGION)
                assertTrue(fold.isExpanded)
                assertEquals(source, editor.document.text)
            }
        }
    }

    @Test
    fun `native folding pairs aliased boundaries using the including entry`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = "OPEN emit (%dest) # header\n    addiu %dest, \$zero, 7\nSTOP # closer\n"
        val documents = PsiDocumentManager.getInstance(current)
        val refresh = current.service<MipsFoldingRefresh>()
        val entry = withContext(Dispatchers.EDT) {
            val plain = fixture.addFileToProject("notes.txt", "Notes")
            FileEditorManager.getInstance(current).openFile(plain.virtualFile, FileEditorOpenRequest.defaults())
            val notes = documents.getDocument(plain)!!
            WriteCommandAction.runWriteCommandAction(current) {
                notes.insertString(notes.textLength, "\n")
                EditorFactory.getInstance().createDocument("").insertString(0, "Scratch")
            }
            val macros = fixture.addFileToProject("fold-macros.s", source)
            val entry = fixture.addFileToProject(
                "fold-entry.asm", ".eqv OPEN .macro\n.eqv STOP .end_macro\n.include \"fold-macros.s\"\n",
            )
            fixture.configureFromExistingVirtualFile(macros.virtualFile)
            entry
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val folding = CodeFoldingManager.getInstance(current)
        val aliases = readAction { documents.getDocument(entry)!! }
        val original = readAction { aliases.text }
        suspend fun changeCloser(replacement: String) {
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(current) {
                    aliases.setText(original.replace(".end_macro", replacement))
                }
                documents.commitDocument(aliases)
            }
            refresh.waitForRefresh()
        }
        withContext(Dispatchers.EDT) {
            val editor = fixture.editor
            folding.updateFoldRegions(editor)
            val fold = editor.foldingModel.allFoldRegions.single()
            assertEquals(source.indexOf('\n'), fold.startOffset)
            assertEquals(source.indexOf("\nSTOP"), fold.endOffset)
            assertEquals(" …", fold.placeholderText)
            editor.caretModel.moveToOffset(source.indexOf("emit"))
            fixture.performEditorAction(IdeActions.ACTION_COLLAPSE_REGION)
            assertFalse(editor.foldingModel.allFoldRegions.single().isExpanded)
            fixture.performEditorAction(IdeActions.ACTION_EXPAND_REGION)
            assertTrue(editor.foldingModel.allFoldRegions.single().isExpanded)
        }
        changeCloser("nop")
        withContext(Dispatchers.EDT) {
            folding.updateFoldRegions(fixture.editor)
            assertTrue(fixture.editor.foldingModel.allFoldRegions.isEmpty())
        }
        changeCloser(".end_macro")
        withContext(Dispatchers.EDT) {
            folding.updateFoldRegions(fixture.editor)
            assertEquals(source.indexOf("\nSTOP"), fixture.editor.foldingModel.allFoldRegions.single().endOffset)
            assertEquals(source, fixture.editor.document.text)
            WriteCommandAction.runWriteCommandAction(current) {
                fixture.editor.document.insertString(source.length, "# notes\n")
            }
            documents.commitDocument(fixture.editor.document)
        }
        refresh.waitForRefresh()
        withContext(Dispatchers.EDT) {
            folding.updateFoldRegions(fixture.editor)
            assertEquals(source.indexOf("\nSTOP"), fixture.editor.foldingModel.allFoldRegions.single().endOffset)
        }
    }

    @Test
    fun `folding refreshes after an unopened include is created changed or deleted on disk`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val current = project.get()
            val entry = directory.resolve("external-fold.asm")
            val alias = directory.resolve("aliases.s")
            val source = ".include \"aliases.s\"\nOPEN emit\n    nop\nSTOP\n"
            val definitions = ".eqv OPEN .macro\n.eqv STOP .end_macro\n"
            val file = withContext(Dispatchers.IO) {
                entry.writeText(source)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry)!!
            }
            withContext(Dispatchers.EDT) { fixture.configureFromExistingVirtualFile(file) }
            val folding = CodeFoldingManager.getInstance(current)
            val refresh = current.service<MipsFoldingRefresh>()
            suspend fun assertFold(expected: Boolean) {
                IndexingTestUtil.suspendUntilIndexesAreReady(current)
                refresh.waitForRefresh()
                withContext(Dispatchers.EDT) {
                    val editor = fixture.editor
                    folding.updateFoldRegions(editor)
                    val regions = editor.foldingModel.allFoldRegions
                    if (expected) {
                        val fold = regions.single()
                        assertEquals(source.indexOf("\n    nop"), fold.startOffset)
                        assertEquals(source.indexOf("\nSTOP"), fold.endOffset)
                    } else {
                        assertTrue(regions.isEmpty())
                    }
                    assertEquals(source, editor.document.text)
                }
            }
            suspend fun changeOnDisk(change: () -> Unit) = withContext(Dispatchers.IO) {
                change()
                VfsUtil.markDirtyAndRefresh(false, true, true, file.parent)
            }
            assertFold(false)
            changeOnDisk { alias.writeText(definitions) }
            assertFold(true)
            changeOnDisk { alias.writeText(definitions.replace(".end_macro", "nop")) }
            assertFold(false)
            changeOnDisk { alias.writeText(definitions) }
            assertFold(true)
            changeOnDisk { Files.delete(alias) }
            assertFold(false)
        }

    @Test
    fun `folding follows the selected assembly entry in a shared editor without source edits`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = "OPEN emit\n    nop\nSTOP\n"
        val (plainEntry, macroEntry) = withContext(Dispatchers.EDT) {
            val macros = fixture.addFileToProject("shared.s", source)
            val include = ".include \"shared.s\"\n"
            val plain = fixture.addFileToProject("plain.asm", ".eqv OPEN .set\n.eqv STOP .set noat\n$include")
            val expanded = fixture.addFileToProject("macro.asm", ".eqv OPEN .macro\n.eqv STOP .end_macro\n${include}emit\n")
            fixture.configureFromExistingVirtualFile(macros.virtualFile)
            plain.virtualFile.toNioPath() to expanded.virtualFile.toNioPath()
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val folding = CodeFoldingManager.getInstance(current)
        val refresh = current.service<MipsFoldingRefresh>()
        suspend fun assertFold(expected: Boolean) = withContext(Dispatchers.EDT) {
            val editor = fixture.editor
            folding.updateFoldRegions(editor)
            val regions = editor.foldingModel.allFoldRegions
            assertEquals(if (expected) 1 else 0, regions.size)
            if (expected) {
                val fold = regions.single()
                assertEquals(source.indexOf('\n'), fold.startOffset)
                assertEquals(source.indexOf("\nSTOP"), fold.endOffset)
            }
            assertEquals(source, editor.document.text)
        }
        suspend fun select(entry: Path) {
            val component = MarsSessionService.createMarsComponent()
            component.context().use {
                assertTrue(current.service<MarsBuildService>().assemble(entry, component, activateBuildView = false))
            }
            refresh.waitForRefresh()
        }
        refresh.waitForRefresh()
        assertFold(false)
        select(macroEntry)
        assertFold(true)
        select(plainEntry)
        assertFold(false)
    }

    @Test
    fun `folding avoids pairing a macro with an extra terminator after an included close`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = ".macro emit\n  nop\n.include \"close.s\"\nother: nop\n.end_macro\n.macro local\n  nop\n.end_macro\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("close.s", ".end_macro\n")
            fixture.configureByText("split-fold.asm", source)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val editor = fixture.editor
            EditorTestUtil.buildInitialFoldingsInBackground(editor)
            assertEquals(2, editor.foldingModel.allFoldRegions.size)
            CodeFoldingManager.getInstance(current).updateFoldRegions(editor)
            val fold = editor.foldingModel.allFoldRegions.single()
            assertEquals(source.indexOf('\n', source.indexOf(".macro local")), fold.startOffset)
            assertEquals(source.lastIndexOf("\n.end_macro"), fold.endOffset)
        }
    }

    @Test
    fun `folding follows valid terminators after unsaved edits during indexing`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.configureByText(PlainTextFileType.INSTANCE, ".macro ignored\nnop\n.end_macro")
            assertTrue(MipsFoldingBuilder().buildFoldRegions(fixture.file, fixture.editor.document, true).isEmpty())
            val header = ".macro pending"
            val body = ".end_macro stray\n.end_macro .include \"part.s\"\ninside: nop"
            val source = ".macro empty\n.end_macro\n.macro blank\n\n.end_macro\n$header\n$body\n"
            fixture.configureByText("unfinished.asm", source)
            val editor = fixture.editor
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                EditorTestUtil.buildInitialFoldingsInBackground(editor)
                assertTrue(editor.foldingModel.allFoldRegions.isEmpty())
                WriteCommandAction.runWriteCommandAction(current) {
                    editor.document.insertString(source.length, ".end_macro,, # real terminator\nnext: nop\n")
                }
                PsiDocumentManager.getInstance(current).commitAllDocuments()
                EditorTestUtil.buildInitialFoldingsInBackground(editor)
                val fold = editor.foldingModel.allFoldRegions.single()
                assertEquals(source.indexOf(header) + header.length, fold.startOffset)
                assertEquals(source.length - 1, fold.endOffset)
                assertEquals("\n$body", editor.document.text.substring(fold.startOffset, fold.endOffset))
                val descriptor = MipsFoldingBuilder().buildFoldRegions(fixture.file, editor.document, false).single()
                assertEquals(fold.textRange, descriptor.range)
            }
        }
    }

    @Test
    fun `platform matches nested parentheses and ignores parentheses in strings and comments`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val source = ".word <caret>(1+(2-3)) # )\n.asciiz \"(\"\n"
            fixture.configureByText(MipsFileType, source)
            val closing = fixture.file.text.indexOf(") #")
            assertEquals(closing, BraceMatchingUtil.getMatchedBraceOffset(fixture.editor, true, fixture.file))
            fixture.editor.caretModel.moveToOffset(closing)
            assertEquals(6, BraceMatchingUtil.getMatchedBraceOffset(fixture.editor, false, fixture.file))

            val matcher = LanguageBraceMatching.INSTANCE.forLanguage(MipsLanguage)
            assertInstanceOf(MipsBraceMatcher::class.java, matcher)
            val pair = matcher.pairs.single()
            assertSame(MipsTypes.psi("LEFT_PAREN"), pair.leftBraceType)
            assertSame(MipsTypes.psi("RIGHT_PAREN"), pair.rightBraceType)
            assertFalse(pair.isStructural)
            assertTrue(matcher.isPairedBracesAllowedBeforeType(pair.leftBraceType, null))
            assertTrue(matcher.isPairedBracesAllowedBeforeType(pair.leftBraceType, MipsTypes.psi("NUMBER")))
            assertEquals(6, matcher.getCodeConstructStart(fixture.file, 6))
        }
    }

    @Test
    fun `registered commenter exposes only the MARS line marker`() {
        val commenter = LanguageCommenters.INSTANCE.forLanguage(MipsLanguage)
        assertInstanceOf(MipsCommenter::class.java, commenter)
        assertEquals("#", commenter.lineCommentPrefix)
        assertNull(commenter.blockCommentPrefix)
        assertNull(commenter.blockCommentSuffix)
        assertNull(commenter.commentedBlockCommentPrefix)
        assertNull(commenter.commentedBlockCommentSuffix)
    }
}
