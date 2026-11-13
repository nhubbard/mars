package edu.missouristate.mars.plugin.help

import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.ide.browsers.WebBrowser
import com.intellij.ide.plugins.PluginDetailsService
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.replaceService
import com.intellij.ui.SearchTextField
import com.intellij.ui.UiInterceptors
import com.intellij.ui.components.BrowserLink
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.plugin.language.MipsDocumentationCatalog
import edu.missouristate.mars.plugin.language.MipsSyscallDocumentation
import java.nio.file.Path
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
        assertEquals(MipsDocumentationCatalog.entries.size + MipsSyscallDocumentation.entries.size + 12, topics.size)
    }

    @Test
    fun `archived help is inert and license topics reproduce the packaged notices`() {
        val topics = MarsReferenceCatalog.topics.associateBy(MarsReferenceTopic::title)
        val credits = topics.getValue("MARS acknowledgements").html()
        val history = topics.getValue("MARS release history").html()
        assertTrue(credits.contains("Mohammad Sekhavat"))
        assertTrue(history.contains("Release 4.5"))
        val tags = Regex("<\\s*/?([a-z][a-z0-9]*)\\b", RegexOption.IGNORE_CASE)
        val allowed = setOf("b", "br", "center", "em", "font", "h3", "h4", "hr", "i", "li", "p", "tt", "ul")
        assertTrue(tags.findAll(credits + history).all { it.groupValues[1].lowercase() in allowed })
        for ((title, filename) in mapOf(
            "Original MARS license" to "MARSlicense.txt",
            "Apache License 2.0" to "Apache-2.0.txt",
            "Third-party notices" to "THIRD-PARTY-NOTICES.txt",
        )) {
            val text = javaClass.getResourceAsStream("/mars-reference/$filename")!!.bufferedReader().use { it.readText() }
            assertEquals("<pre>${escapeXmlEntities(text)}</pre>", topics.getValue(title).html())
        }
    }

    @Test
    fun `About shows the installed version and opens the reference card only when requested`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val lifetime = Disposer.newDisposable()
            val opened = mutableListOf<String>()
            ApplicationManager.getApplication().replaceService(BrowserLauncher::class.java, object : BrowserLauncher() {
                override fun open(url: String) = error("Expected browser navigation")
                override fun browse(file: Path) = error("Expected the upstream card URL")
                override fun browse(url: String, browser: WebBrowser?, project: Project?) {
                    opened.add(url)
                }
            }, lifetime)
            try {
                inspectDialog("Mars.About") { dialog ->
                    val details = UIUtil.findComponentsOfType(dialog.contents, JBHtmlPane::class.java).single()
                    val version = PluginDetailsService.getInstance().findDetails(PluginId.getId("edu.missouristate.mars"))!!.version!!
                    assertTrue(details.text.contains("Installed MARS MIPS plugin version $version"))
                    assertTrue(details.text.contains("Pete Sanderson and Kenneth Vollmar"))
                    assertTrue(opened.isEmpty())
                    UIUtil.findComponentsOfType(dialog.contents, BrowserLink::class.java).single().doClick(0)
                    assertEquals(listOf("https://github.com/dpetersanderson/MARS/blob/main/help/mipsref.pdf"), opened)
                }
            } finally {
                Disposer.dispose(lifetime)
            }
        }
    }

    @Test
    fun `registered Help action opens the reference and searches offline without a project`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            inspectDialog("Mars.OpenReference") { dialog ->
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
            }
        }
    }

    private fun inspectDialog(actionId: String, inspect: (MarsReferenceDialog) -> Unit) {
        val action = ActionManager.getInstance().getAction(actionId)
        var inspected = false
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<MarsReferenceDialog>(MarsReferenceDialog::class.java) {
            override fun doIntercept(dialog: MarsReferenceDialog) {
                try {
                    inspect(dialog)
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
