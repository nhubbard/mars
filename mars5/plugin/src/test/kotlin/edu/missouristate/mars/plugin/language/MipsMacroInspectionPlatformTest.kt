package edu.missouristate.mars.plugin.language

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.language.inspection.MipsMacroParameterInspection
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsMacroInspectionPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)

    @Test
    fun `uninvoked macros diagnose duplicate formals and unknown parameters at their source tokens`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val inspection = MipsMacroParameterInspection()
            assertTrue(inspection.runForWholeFile(), "Header edits must refresh diagnostics throughout the macro")
            fixture.enableInspections(inspection)
            fixture.configureByText("plain.asm", "nop")
            assertTrue(warnings().isEmpty())
            val source = """
                .text
                .macro example (%used, %used, ${'$'}ok, ${'$'}ok)
                    addiu ${'$'}t0, ${'$'}zero, %used
                    addiu ${'$'}t1, ${'$'}zero, ${'$'}ok
                    addiu ${'$'}t2, ${'$'}zero, %missing
                    addiu ${'$'}t3, ${'$'}zero, ${'$'}missing
                %destination: nop
                    .asciiz "%quoted ${'$'}quoted"
                    # %comment
                .end_macro
                addiu ${'$'}t4, ${'$'}zero, %outside
            """.trimIndent()
            fixture.configureByText("parameters.asm", source)
            assertEquals(
                listOf(
                    Warning(source.indexOf("%used, %used") + "%used, ".length, "%used", duplicate("%used")),
                    Warning(source.indexOf("${'$'}ok, ${'$'}ok") + "${'$'}ok, ".length, "${'$'}ok", duplicate("${'$'}ok")),
                    Warning(source.indexOf("%missing"), "%missing", unknown("example", "%missing")),
                    Warning(source.indexOf("${'$'}missing"), "${'$'}missing", unknown("example", "${'$'}missing")),
                    Warning(source.indexOf("%destination"), "%destination", unknown("example", "%destination")),
                ),
                warnings(),
            )
            WriteCommandAction.runWriteCommandAction(fixture.project) {
                val headerEnd = source.indexOf(')')
                fixture.editor.document.insertString(headerEnd, ", %missing, ${'$'}missing, %destination")
            }
            assertEquals(listOf(duplicate("%used"), duplicate("${'$'}ok")), warnings().map(Warning::message))
        }
    }

    @Test
    fun `alias generated parameters point to the original alias and ignore invalid expansions`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.enableInspections(MipsMacroParameterInspection::class.java)
            val source = """
                .eqv BEGIN .macro
                .eqv END .end_macro
                .eqv FORM %item, %item, %item
                .eqv UNKNOWN %absent, %absent
                .eqv BOUND %item
                .eqv FIRST SECOND
                .eqv SECOND FIRST
                BEGIN aliased (FORM)
                    addiu ${'$'}t0, ${'$'}zero, BOUND
                    .word UNKNOWN
                    .word %skipped, FIRST
                    ,,,
                    .macro invalid (%nested)
                    .end_macro %ignored
                END
                .macro direct (FORM)
                    addiu ${'$'}t0, ${'$'}zero, BOUND
                .end_macro
                .macro valid (%ok)
                    addiu ${'$'}t0, ${'$'}zero, %ok
                .end_macro
                nop
            """.trimIndent()
            fixture.configureByText("mapped.s", source)
            assertEquals(
                listOf(
                    Warning(source.indexOf("(FORM)") + 1, "FORM", duplicate("%item")),
                    Warning(source.indexOf(".word UNKNOWN") + ".word ".length, "UNKNOWN", unknown("aliased", "%absent")),
                    Warning(source.lastIndexOf("(FORM)") + 1, "FORM", duplicate("%item")),
                ),
                warnings(),
            )
        }
    }

    @Test
    fun `a malformed expanded closer reports its written token and leaves the macro open`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val source = """
                .eqv CLOSE .EnD_MaCrO
                .eqv BAD .end_macro extra
                .eqv LABEL outside:
                .macro emit (%value)
                CLOSE 7
                BAD
                body: addiu ${'$'}t0, ${'$'}zero, %value
                CLOSE # actual closer
                emit(42)
                LABEL .end_macro extra
            """.trimIndent()
            val entry = directory.resolve("bad-closers.asm").also { it.writeText(source.replace("emit(42)\n", "")) }
            val context = projectFixture.get().service<MarsSessionService>().context
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
            assertEquals(
                List(3) { "invalid text after .END_MACRO" },
                failure.errors().errorMessages.map { it.message },
            )
            withContext(Dispatchers.EDT) { fixture.configureByText("bad-closers.asm", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
            withContext(Dispatchers.EDT) {
                assertEquals(
                    listOf("CLOSE" to trailingCloseMessage, "BAD" to trailingCloseMessage, ".end_macro" to trailingCloseMessage),
                    errors(),
                )
                readAction {
                    val file = fixture.file
                    assertEquals("%value", file.findReferenceAt(source.lastIndexOf("%value") + 1)?.resolve()?.text)
                    assertEquals("emit", (file.findReferenceAt(source.indexOf("emit(42)"))?.resolve() as? MipsDeclaration)?.name)
                }
                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    fixture.editor.document.setText(source.replace("CLOSE 7\nBAD\n", "").substringBefore("\nLABEL "))
                }
                assertTrue(errors().isEmpty())
            }
        }

    @Test
    fun `a shared malformed closer waits for the selected entry`() = timeoutRunBlocking<Unit> {
        val current = projectFixture.get()
        val entry = withContext(Dispatchers.EDT) {
            val first = fixture.addFileToProject("first.asm", ".include \"shared-close.s\"\n")
            fixture.addFileToProject("second.asm", ".include \"shared-close.s\"\n")
            val shared = fixture.addFileToProject("shared-close.s", ".eqv STOP .end_macro\n.macro emit\nSTOP extra\n.end_macro\n")
            fixture.configureFromExistingVirtualFile(shared.virtualFile)
            first.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            assertTrue(errors().isEmpty())
            current.service<MarsAssemblySettings>().changeSources(listOf(entry.path))
            assertEquals(listOf("STOP" to trailingCloseMessage), errors())
        }
    }

    @Test
    fun `a malformed alias closer waits for an unresolved earlier include`() = timeoutRunBlocking<Unit> {
        val source = ".include \"pending.s\"\n.eqv STOP .end_macro\n.macro emit\nSTOP extra\n.end_macro\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("entry.asm", source).also { fixture.configureFromExistingVirtualFile(it.virtualFile) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) {
            assertTrue(errors().none { it.second == trailingCloseMessage })
            fixture.addFileToProject("pending.s", "nop\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) { assertEquals(listOf("STOP" to trailingCloseMessage), errors()) }
    }

    @Test
    fun `unmatched closers and the unclosed opener point to their written tokens`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val source = """
                .eqv OPEN .macro
                .eqv STOP .end_macro
                .eqv LABEL alias_label
                .end_macro
                STOP
                outside: .end_macro # the assembler strips this label
                LABEL: .end_macro
                OPEN emit (%value)
                inside: .end_macro # macro bodies retain their labels
                li ${'$'}t0, %value
            """.trimIndent()
            val entry = directory.resolve("boundaries.asm").also { it.writeText(source) }
            val context = projectFixture.get().service<MarsSessionService>().context
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
            assertEquals(
                List(4) { ".END_MACRO without .MACRO" } + "Macro started but not ended (no .end_macro directive)",
                failure.errors().errorMessages.map { it.message },
            )
            withContext(Dispatchers.EDT) { fixture.configureByText("boundaries.asm", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
            withContext(Dispatchers.EDT) {
                assertEquals(
                    listOf(".end_macro", "STOP", ".end_macro", ".end_macro").map { it to unmatchedEndMessage } +
                        ("OPEN" to missingEndMessage),
                    errors(),
                )
                assertEquals("%value", fixture.file.findReferenceAt(source.lastIndexOf("%value") + 1)?.resolve()?.text)
                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    val repairedBody = source.substringAfter("LABEL: .end_macro\n").lineSequence()
                        .filterNot { it.startsWith("inside:") }.joinToString("\n")
                    fixture.editor.document.setText(source.substringBefore("\n.end_macro") + "\n" + repairedBody + "\nSTOP\nemit(1)\n")
                }
                assertEquals(emptyList<Pair<String, String>>(), errors())
            }
        }

    @Test
    fun `an invalid expanded label does not expose a trailing closer as an operation`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.configureByText("not-closers.asm", """
                .eqv NUMBER 7
                .eqv DOUBLE one: two
                .eqv FIRST SECOND
                .eqv SECOND FIRST
                NUMBER: .end_macro
                DOUBLE: .end_macro
                FIRST: .end_macro
                .word .end_macro
                '1234\#' .end_macro
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) { assertTrue(errors().none { it.second == unmatchedEndMessage }) }
    }

    @Test
    fun `macro boundaries follow the selected entry through includes`() = timeoutRunBlocking<Unit> {
        val current = projectFixture.get()
        val (valid, open) = withContext(Dispatchers.EDT) {
            val opener = fixture.addFileToProject("opener.s", ".macro emit\nnop\n")
            fixture.addFileToProject("closer.s", ".end_macro\n")
            val valid = fixture.addFileToProject("valid.asm", ".include \"opener.s\"\n.include \"closer.s\"\n")
            val open = fixture.addFileToProject("open.asm", ".include \"opener.s\"\n")
            fixture.configureFromExistingVirtualFile(opener.virtualFile)
            valid.virtualFile to open.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val settings = current.service<MarsAssemblySettings>()
            assertTrue(errors().isEmpty())
            settings.changeSources(listOf(open.path))
            assertEquals(listOf(".macro" to missingEndMessage), errors())
            settings.changeSources(listOf(valid.path))
            assertTrue(errors().isEmpty())
        }
    }

    @Test
    fun `a pending include can supply either macro boundary`() = timeoutRunBlocking<Unit> {
        val source = ".include \"start.s\"\n.end_macro\n.macro second\n.include \"finish.s\"\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("entry.asm", source).also { fixture.configureFromExistingVirtualFile(it.virtualFile) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) {
            assertTrue(errors().none { it.second == unmatchedEndMessage || it.second == missingEndMessage })
            fixture.addFileToProject("start.s", ".macro first\n")
            fixture.addFileToProject("finish.s", ".end_macro\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) { assertTrue(errors().isEmpty()) }
    }

    @Test
    fun `invalid expanded formals point to their written tokens and refresh after edits`() = timeoutRunBlocking<Unit> {
        val source = """
            .eqv FORMS %kept, 7, 8
            .macro emit (FORMS)
                li ${'$'}t0, %kept
            .end_macro
            .macro literal (%first, 9)
                nop
            .end_macro
            .eqv OPEN .macro
            OPEN generated (ordinary)
                nop
            .end_macro
        """.trimIndent()
        withContext(Dispatchers.EDT) { fixture.configureByText("bad-formals.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) {
            assertEquals(
                listOf(
                    "FORMS" to "Invalid MARS macro parameter '7'",
                    "9" to "Invalid MARS macro parameter",
                    "ordinary" to "Invalid MARS macro parameter 'ordinary'",
                ),
                errors(),
            )
            val use = source.indexOf("%kept", source.indexOf("li"))
            assertEquals("FORMS", fixture.file.findReferenceAt(use + 1)?.resolve()?.text)
            WriteCommandAction.runWriteCommandAction(fixture.project) {
                fixture.editor.document.setText(
                    source.replace("7, 8", "%second, %third").replace("9)", "%second)").replace("(ordinary)", "(%ordinary)"),
                )
            }
            assertTrue(errors().isEmpty())
        }
    }

    @Test
    fun `a lexical error on a generated header gets one diagnostic`() = timeoutRunBlocking<Unit> {
        val source = ".eqv OPEN .macro\n.eqv FORM %value\nOPEN emit (FORM, @)\nli \$t0, %value\n.end_macro\n"
        withContext(Dispatchers.EDT) { fixture.configureByText("bad-generated-formal.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(projectFixture.get())
        withContext(Dispatchers.EDT) {
            assertEquals(1, errors().size)
            assertEquals("FORM", fixture.file.findReferenceAt(source.lastIndexOf("%value") + 1)?.resolve()?.text)
        }
    }

    @Test
    fun `a shared macro header waits for an entry before diagnosing its expanded formal`() = timeoutRunBlocking<Unit> {
        val current = projectFixture.get()
        val (valid, invalid) = withContext(Dispatchers.EDT) {
            val first = fixture.addFileToProject("valid.asm", ".eqv FORM %value\n.include \"shared.s\"\n")
            val second = fixture.addFileToProject("invalid.asm", ".eqv FORM 17\n.include \"shared.s\"\n")
            val shared = fixture.addFileToProject("shared.s", ".macro emit (FORM)\nnop\n.end_macro\n")
            fixture.configureFromExistingVirtualFile(shared.virtualFile)
            first.virtualFile to second.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            assertTrue(errors().isEmpty())
            val settings = current.service<MarsAssemblySettings>()
            settings.changeSources(listOf(invalid.path))
            assertEquals(listOf("FORM" to "Invalid MARS macro parameter '17'"), errors())
            settings.changeSources(listOf(valid.path))
            assertTrue(errors().isEmpty())
        }
    }

    private val trailingCloseMessage = "Unexpected text after .end_macro"
    private val unmatchedEndMessage = ".end_macro without a matching .macro"
    private val missingEndMessage = "Missing .end_macro for this macro"

    private fun errors(): List<Pair<String, String>> = fixture.doHighlighting()
        .filter { it.severity == HighlightSeverity.ERROR }
        .sortedBy { it.startOffset }
        .map { fixture.file.text.substring(it.startOffset, it.endOffset) to it.description.orEmpty() }

    @Test
    fun `included body uses the enclosing macro from the inferred and selected assembly entry`() = timeoutRunBlocking<Unit> {
        val project = projectFixture.get()
        val (entry, body) = withContext(Dispatchers.EDT) {
            fixture.enableInspections(MipsMacroParameterInspection::class.java)
            fixture.addFileToProject("macro-start.s", ".macro scoped (%used)\n")
            val body = fixture.addFileToProject("macro-body.s", "addiu ${'$'}t0, ${'$'}zero, %used\naddiu ${'$'}t1, ${'$'}zero, %absent\n")
            val entry = fixture.addFileToProject("entry.asm", """
                .text
                .include "macro-start.s"
                .include "macro-body.s"
                .end_macro
                nop
            """.trimIndent())
            fixture.configureFromExistingVirtualFile(body.virtualFile)
            entry.virtualFile to body.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        fun expectedWarnings(): List<Warning> {
            val text = fixture.file.text
            return listOf(Warning(text.indexOf("%absent"), "%absent", unknown("scoped", "%absent")))
        }
        withContext(Dispatchers.EDT) { assertEquals(expectedWarnings(), warnings()) }
        val component = MarsSessionService.createMarsComponent()
        component.context().use {
            assertTrue(project.service<MarsBuildService>().assemble(entry.toNioPath(), component, activateBuildView = false))
        }
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(entry)
            assertTrue(warnings().isEmpty())
            fixture.configureFromExistingVirtualFile(body)
            assertEquals(expectedWarnings(), warnings())
        }
    }

    private data class Warning(val offset: Int, val token: String, val message: String)

    private fun warnings(): List<Warning> = fixture.doHighlighting()
        .filter { it.severity == HighlightSeverity.WARNING }
        .mapNotNull { info ->
            val message = info.description ?: return@mapNotNull null
            if (!message.startsWith("Duplicate macro parameter") && !message.startsWith("Macro '")) return@mapNotNull null
            Warning(info.startOffset, fixture.file.text.substring(info.startOffset, info.endOffset), message)
        }
        .sortedBy(Warning::offset)

    private fun duplicate(name: String) = "Duplicate macro parameter '$name'. References bind to the first declaration."
    private fun unknown(macro: String, name: String) = "Macro '$macro' has no parameter named '$name'."
}
