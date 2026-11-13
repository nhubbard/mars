package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@TestApplication
class BuiltLabelCompletionPlatformTest {
    @Test
    fun `a build survives only changes to the current ordinary instruction line`() = timeoutRunBlocking {
        val cases = listOf(
            Triple("nop\nj old\nnop", "nop\nj gen<caret>\nnop", true),
            Triple("nop", "nop\nj gen<caret>", true),
            Triple("nop\n", "nop\nj gen<caret>", true),
            Triple("# comment\nnop", "j gen<caret>\nnop", true),
            Triple("nop", "nop\n\nj gen<caret>", false),
            Triple("emit\nj old", "call\nj gen<caret>", false),
            Triple("j old\nnop", "j gen<caret>\nnop\nnop", false),
            Triple("j old\nnop", "j gen<caret>\neret", false),
            Triple("j old\nnop", "j gen<caret>\nadd", false),
            Triple("emit", "j gen<caret>", false),
            Triple("nop: nop", "j gen<caret>", false),
            Triple("j old", "j gen<caret> .include \"other.s\"", false),
            Triple("j old", "j gen<caret> @", false),
        )
        readAction {
            for ((previous, marked, expected) in cases) {
                val document = EditorFactory.getInstance().createDocument(marked.replace("<caret>", ""))
                assertEquals(expected, preservesBuiltLabelNames(previous, document, marked.indexOf("<caret>")), marked)
            }
        }
    }
}
