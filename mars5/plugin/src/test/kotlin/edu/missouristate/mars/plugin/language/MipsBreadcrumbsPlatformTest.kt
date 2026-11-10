package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.breadcrumbs.FileBreadcrumbsCollector
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.xml.breadcrumbs.NavigatableCrumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@TestApplication
class MipsBreadcrumbsPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native collector follows written segments macro and label boundaries`() = timeoutRunBlocking<Unit> {
        val source = """
            # outside all declarations
            start: nop
            .macro emit (%name)
                nop # before first local label
                %name: nop
                addiu ${'$'}a0, ${'$'}zero, 42
            .end_macro
            nop # after the macro definition
            .data
            item: .word 4
            .eqv NEXT .text
            .text
            syscall # no label in this segment yet
        """.trimIndent()
        withContext(Dispatchers.EDT) { fixture.configureByText("main.asm", source) }
        readAction {
            fun at(text: String) = crumbs(source.indexOf(text)).map { it.text }
            assertEquals(listOf("main.asm"), at("outside"))
            assertEquals(listOf("main.asm", "start"), at("start:"))
            assertEquals(listOf("main.asm", "emit"), at("before first"))
            assertEquals(listOf("main.asm", "emit", "%name"), at("addiu"))
            assertEquals(listOf("main.asm", "start"), at("after the macro"))
            assertEquals(listOf("main.asm", ".data", "item"), at("NEXT"))
            assertEquals(listOf("main.asm", ".data", "item"), at(".text\n.text"))
            assertEquals(listOf("main.asm", ".text"), at("syscall"))
            assertFalse(MipsBreadcrumbsProvider().acceptStickyElement(fixture.file))
        }
    }

    @Test
    fun `native breadcrumb navigation and subsequent edits use current source offsets`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = ".text\nmain: nop\n    addiu ${'$'}a0, ${'$'}zero, 42\n"
        withContext(Dispatchers.EDT) { fixture.configureByText("entry.s", source) }
        val initial = readAction { crumbs(source.indexOf("addiu")) }
        assertEquals(listOf("entry.s", ".text", "main"), initial.map { it.text })
        val updated = "# heading\n" + source.replace("main:", "entry:")
        withContext(Dispatchers.EDT) {
            (initial.last() as NavigatableCrumb).navigate(fixture.editor, false)
            assertEquals(source.indexOf("main:"), fixture.editor.caretModel.offset)
            val document = fixture.editor.document
            WriteCommandAction.runWriteCommandAction(current) { document.setText(updated) }
            PsiDocumentManager.getInstance(current).commitDocument(document)
        }
        val refreshed = readAction { crumbs(updated.indexOf("addiu")) }
        assertEquals(listOf("entry.s", ".text", "entry"), refreshed.map { it.text })
        withContext(Dispatchers.EDT) {
            (refreshed.last() as NavigatableCrumb).navigate(fixture.editor, false)
            assertEquals(updated.indexOf("entry:"), fixture.editor.caretModel.offset)
        }
    }

    private fun crumbs(offset: Int) = fixture.file.virtualFile.let { file ->
        FileBreadcrumbsCollector.findBreadcrumbsCollector(project.get(), file)
            .computeCrumbs(file, fixture.editor.document, offset, true).toList()
    }
}
