package edu.missouristate.mars.plugin.language

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.language.inspection.MipsMacroParameterInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
