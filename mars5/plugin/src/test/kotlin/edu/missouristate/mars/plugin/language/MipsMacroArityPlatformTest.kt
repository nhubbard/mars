package edu.missouristate.mars.plugin.language

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.inspection.MipsMacroArityInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsMacroArityPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `uses only closed overloads visible at the call and updates after header edits`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val inspection = MipsMacroArityInspection()
            assertTrue(inspection.runForWholeFile(), "A changed overload must refresh later calls")
            fixture.enableInspections(inspection)
            val source = """
                .text
                emit()
                .macro emit (%one)
                    nop
                .end_macro
                emit 1 2
                .macro emit (%left %right)
                    emit 1 2 3
                .end_macro
                emit 1 2 3
                label: emit(1,2)
                emit(1
                emit (-7, 2)
                emit(,1,,2,) # ignored commas
                emit "a b", 2
                emit()
                standalone:
                nop
            """.trimIndent()
            fixture.configureByText("arity.asm", source)
            val file = fixture.file as MipsFile
            val analysis = file.project.service<MarsAnalysisService>()
            assertTrue(analysis.macroTargets(file, file.findElementAt(source.indexOf("emit 1 2"))!!).isEmpty())
            assertTrue(analysis.macroTargets(file, file.findElementAt(source.lastIndexOf("emit 1 2 3"))!!).isEmpty())
            assertTrue(analysis.macroTargets(file, file.findElementAt(source.lastIndexOf("nop"))!!).isEmpty())
            assertEquals(
                source.indexOf("emit (%left"),
                analysis.macroTargets(file, file.findElementAt(source.indexOf("emit (-7"))!!).single().textOffset,
            )
            assertEquals(
                listOf(
                    Warning(source.indexOf("emit 1 2"), "emit", message("emit", 2, "1")),
                    Warning(source.lastIndexOf("emit 1 2 3"), "emit", message("emit", 3, "1, 2")),
                    Warning(source.lastIndexOf("emit()"), "emit", message("emit", 0, "1, 2")),
                ),
                warnings(),
            )
            WriteCommandAction.runWriteCommandAction(fixture.project) {
                val end = source.indexOf("%right") + "%right".length
                fixture.editor.document.insertString(end, ", %third")
            }
            assertEquals(
                listOf(message("emit", 2, "1"), message("emit", 2, "1, 3"),
                    message("emit", 2, "1, 3"), message("emit", 2, "1, 3"), message("emit", 2, "1, 3"),
                    message("emit", 2, "1, 3"), message("emit", 0, "1, 3")),
                warnings().map(Warning::message),
            )
        }
    }

    @Test
    fun `included overloads and expanded call names use the physical alias range`() = timeoutRunBlocking {
        val source = """
            .text
            .include "overloads.s"
            .eqv CALL load
            .eqv FULL generated: load
            .eqv SEP :
            .eqv MARKER only_label:
            .eqv HEAD load 1
            .eqv FIRST SECOND
            .eqv SECOND FIRST
            .eqv NO_CALL nop
            site: CALL ()
            FULL 1 2 3
            addiu SEP load(1)
            split SEP load(1)
            HEAD: nop
            MARKER
            CALL FIRST
            CALL (1, 2)
            NO_CALL
            .eqv LATER load
            LATER (1, 2)
        """.trimIndent()
        withContext(Dispatchers.EDT) {
            fixture.enableInspections(MipsMacroArityInspection::class.java)
            fixture.addFileToProject("overloads.s", ".macro load (%first, %second)\nnop\n.end_macro\n")
            fixture.configureByText("aliases.asm", source)
        }
        withContext(Dispatchers.EDT) {
            assertEquals(
                listOf(
                    Warning(source.indexOf("CALL ()"), "CALL", message("load", 0, "2")),
                    Warning(source.indexOf("FULL 1"), "FULL", message("load", 3, "2")),
                    Warning(source.indexOf("addiu SEP load") + "addiu SEP ".length, "load", message("load", 1, "2")),
                    Warning(source.indexOf("split SEP load") + "split SEP ".length, "load", message("load", 1, "2")),
                    Warning(source.indexOf("HEAD:"), "HEAD", message("load", 3, "2")),
                ),
                warnings(),
            )
            val macroColors = fixture.highlightAfterMipsRefresh().filter { it.forcedTextAttributesKey == macroColor }
                .map { it.startOffset }.toSet()
            assertFalse(source.indexOf("CALL ()") in macroColors)
            assertFalse(source.indexOf("FULL 1") in macroColors)
            assertFalse(source.indexOf("split SEP load") + "split SEP ".length in macroColors)
            assertTrue(source.indexOf("CALL (1, 2)") in macroColors)
            fixture.configureByText("plain.s", ".text\nnop\n")
            assertTrue(warnings().isEmpty())
        }
    }

    @Test
    fun `macro hints yield to valid data continuations when no overload matches`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            fixture.enableInspections(MipsMacroArityInspection::class.java)
            val source = """
                .macro emit (%one)
                .end_macro
                .macro matched (%value, %separator, %count)
                .end_macro
                .macro .dotted (%one)
                .end_macro
                .eqv CALL emit
                .data
                emit: .word 0
                emit 7:2
                CALL 9:2
                emit 2 3
                matched 5:2
                emit 1:0
                emit 2 (
                emit unknown 3
                .dotted 7:2
                .ascii ""
                emit 6:2
                .float 0
                emit .5:2
                emit 1e300:2
                emit 0x10:2
                emit 2 3
                .text
                emit 7:2
                .macro empty (%one)
                .end_macro
                empty:
            """.trimIndent()
            fixture.configureByText("data-fallback.mars", source)
            val file = fixture.file as MipsFile
            val analysis = file.project.service<MarsAnalysisService>()
            fun hints(line: String, offset: Int = source.indexOf(line)) =
                analysis.macroInvocation(file, file.findElementAt(offset)!!, line.substringBefore(' ') + " ")
            for (line in listOf("emit 7:2", "CALL 9:2", "emit 2 3", "emit .5:2")) assertNull(hints(line), line)
            for (line in listOf("matched 5:2", "emit 1:0", "emit 2 (", "emit 1e300:2")) assertNotNull(hints(line), line)
            assertNotNull(hints("emit 7:2", source.lastIndexOf("emit 7:2")))
            assertNotNull(analysis.macroInvocation(file, file.findElementAt(source.indexOf("empty:"))!!, "empty "))
            assertEquals(
                listOf(
                    Warning(source.indexOf("emit 1:0"), "emit", message("emit", 3, "1")),
                    Warning(source.indexOf("emit 2 ("), "emit", message("emit", 2, "1")),
                    Warning(source.indexOf("emit unknown 3"), "emit", message("emit", 2, "1")),
                    Warning(source.indexOf(".dotted 7:2"), ".dotted", message(".dotted", 3, "1")),
                    Warning(source.indexOf("emit 6:2"), "emit", message("emit", 3, "1")),
                    Warning(source.indexOf("emit 1e300:2"), "emit", message("emit", 3, "1")),
                    Warning(source.indexOf("emit 0x10:2"), "emit", message("emit", 3, "1")),
                    Warning(source.lastIndexOf("emit 2 3"), "emit", message("emit", 2, "1")),
                    Warning(source.lastIndexOf("emit 7:2"), "emit", message("emit", 3, "1")),
                ),
                warnings(),
            )
        }
    }

    private data class Warning(val offset: Int, val token: String, val message: String)

    private suspend fun warnings(): List<Warning> = fixture.highlightAfterMipsRefresh()
        .filter { it.severity == HighlightSeverity.WARNING }
        .mapNotNull { info ->
            val message = info.description ?: return@mapNotNull null
            if (!message.contains("has no overload for an argument count")) return@mapNotNull null
            Warning(info.startOffset, fixture.file.text.substring(info.startOffset, info.endOffset), message)
        }
        .sortedBy(Warning::offset)

    private fun message(name: String, actual: Int, expected: String) =
        "Macro '$name' has no overload for an argument count of $actual. Available counts are $expected."
}
