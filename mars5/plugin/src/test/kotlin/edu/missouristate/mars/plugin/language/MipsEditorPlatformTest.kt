package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.options.colors.ColorSettingsPages
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
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
import java.awt.Color
import java.awt.Font

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
                assertEquals(literal.textRange, analysis.operationSite(file, literal)?.range)
                assertEquals("addiu", analysis.editorInstructionTokens(file, literal, literal.text)?.get(0)?.value)
            }
        }
    }

    @Test
    fun `color settings expose a mnemonic preview and distinct light and dark defaults`() {
        val page = ColorSettingsPages.getInstance().registeredPages.filterIsInstance<MipsColorSettingsPage>().single()
        assertEquals("MARS MIPS", page.displayName)
        assertSame(MipsFileType.icon, page.icon)
        assertInstanceOf(MipsSyntaxHighlighter::class.java, page.highlighter)
        assertTrue(page.colorDescriptors.isEmpty())
        val descriptor = page.attributeDescriptors.single()
        assertEquals("Instruction mnemonic", descriptor.displayName)
        assertSame(instructionMnemonicColor, descriptor.key)
        assertEquals(mapOf("instruction" to instructionMnemonicColor), page.additionalHighlightingTagToDescriptorMap)
        assertTrue(page.demoText.contains("<instruction>li</instruction>"))
        assertSame(DefaultLanguageHighlighterColors.KEYWORD, instructionMnemonicColor.fallbackAttributeKey)

        val colors = EditorColorsManager.getInstance()
        for ((name, accent) in mapOf("Default" to 0x0033B3, "Darcula" to 0xCF8E6D)) {
            val scheme = checkNotNull(colors.getScheme(name)) { name }
            val mnemonic = scheme.getAttributes(instructionMnemonicColor)
            assertEquals(Color(accent), mnemonic.foregroundColor, name)
            assertEquals(Font.BOLD, mnemonic.fontType, name)
            assertNotEquals(scheme.getAttributes(HighlighterColors.TEXT).foregroundColor, mnemonic.foregroundColor, name)
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
