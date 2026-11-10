package edu.missouristate.mars.plugin.help

import com.intellij.DynamicBundle
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.openapi.util.text.StringUtil.stripHtml
import edu.missouristate.mars.plugin.language.MipsDocumentationCatalog
import edu.missouristate.mars.plugin.language.MipsSyscallDocumentation
import org.jetbrains.annotations.Nls

private val guides = DynamicBundle(MarsReferenceCatalog::class.java, "messages.MarsReference")
internal val referenceMessages = DynamicBundle(MarsReferenceCatalog::class.java, "messages.MarsBundle")

internal class MarsReferenceTopic(val title: @Nls String, private val searchText: String, val html: () -> @Nls String) {
    fun matches(words: List<String>): Boolean = words.all {
        title.contains(it, ignoreCase = true) || searchText.contains(it, ignoreCase = true)
    }
}

internal object MarsReferenceCatalog {
    val topics: List<MarsReferenceTopic> by lazy {
        buildList {
            for (id in listOf("start", "macros", "debug", "exceptions", "cli", "tools")) {
                val title = guides.getMessage("$id.title")
                val body = guides.getMessage("$id.body")
                add(MarsReferenceTopic(title, stripHtml(body, true)) { body })
            }
            for (entry in MipsDocumentationCatalog.entries) {
                add(MarsReferenceTopic(entry.name, entry.searchText, entry::referenceHtml))
            }
            for (entry in MipsSyscallDocumentation.entries) {
                val title = referenceMessages.getMessage("reference.syscall.title", entry.number, entry.name)
                add(MarsReferenceTopic(title, "${entry.arguments} ${entry.result}") {
                    val arguments = escapeXmlEntities(referenceMessages.getMessage("documentation.syscalls.arguments"))
                    val result = escapeXmlEntities(referenceMessages.getMessage("documentation.syscalls.result"))
                    "<p><b>$arguments</b> ${escapeXmlEntities(entry.arguments)}</p>" +
                        "<p><b>$result</b> ${escapeXmlEntities(entry.result)}</p>"
                })
            }
        }
    }
}
