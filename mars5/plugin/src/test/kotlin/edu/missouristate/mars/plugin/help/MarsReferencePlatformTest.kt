package edu.missouristate.mars.plugin.help

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.SearchTextField
import com.intellij.ui.UiInterceptors
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.plugin.language.MipsDocumentationCatalog
import edu.missouristate.mars.plugin.language.MipsSyscallDocumentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsReferencePlatformTest {
    @Test
    fun `reference includes all instruction directive and syscall entries with their editor descriptions`() {
        val topics = MarsReferenceCatalog.topics.associateBy(MarsReferenceTopic::title)
        for (entry in MipsDocumentationCatalog.entries) {
            assertEquals(entry.referenceHtml(), topics.getValue(entry.name).html())
        }
        for (entry in MipsSyscallDocumentation.entries) {
            val title = "Syscall ${entry.number} · ${entry.name}"
            assertTrue(topics.getValue(title).matches(listOf(entry.name)))
        }
        assertEquals(MipsDocumentationCatalog.entries.size + MipsSyscallDocumentation.entries.size + 6, topics.size)
    }

    @Test
    fun `registered Help action opens the reference and searches offline without a project`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val action = ActionManager.getInstance().getAction("Mars.OpenReference")
            var inspected = false
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<MarsReferenceDialog>(MarsReferenceDialog::class.java) {
                override fun doIntercept(dialog: MarsReferenceDialog) {
                    try {
                        val content = dialog.contents
                        val search = UIUtil.findComponentsOfType(content, SearchTextField::class.java).single()
                        val topics = UIUtil.findComponentsOfType(content, JBList::class.java).single()
                        val details = UIUtil.findComponentsOfType(content, JBHtmlPane::class.java).single()
                        assertEquals(search.textEditor, dialog.preferredFocusedComponent)
                        assertEquals("Mars.Reference", dialog.dimensionKey)
                        assertTrue(details.text.contains("Getting started with MARS"))

                        search.text = "  sYsCaLl 4 PrInTsTrInG "
                        assertEquals(1, topics.model.size)
                        assertTrue(details.text.contains("PrintString"))
                        assertTrue(details.text.contains("null-terminated string"))

                        search.text = ".eqv"
                        val directive = (0 until topics.model.size).single { index ->
                            (topics.model.getElementAt(index) as MarsReferenceTopic).title == ".eqv"
                        }
                        topics.selectedIndex = directive
                        assertTrue(details.text.contains("Substitute second operand for first"))

                        search.text = "no-mips-topic-for-this-query"
                        assertEquals(0, topics.model.size)
                        assertEquals("No matching topics", topics.emptyText.text)
                        assertTrue(details.document.getText(0, details.document.length).isBlank())

                        search.text = ""
                        assertTrue(details.text.contains("Getting started with MARS"))
                        inspected = true
                    } finally {
                        dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                    }
                }
            })
            try {
                val event = AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
                action.actionPerformed(event)
                assertTrue(inspected)
            } finally {
                UiInterceptors.clear()
            }
        }
    }
}
