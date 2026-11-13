package edu.missouristate.mars.plugin.help

import com.intellij.DynamicBundle
import com.intellij.ide.plugins.PluginDetailsService
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.openapi.util.text.StringUtil.stripHtml
import com.intellij.util.ResourceUtil.loadText
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
    val about: MarsReferenceTopic by lazy {
        val title = guides.getMessage("about.title")
        val body = guides.getMessage("about.body")
        MarsReferenceTopic(title, stripHtml(body, true)) {
            val version = PluginDetailsService.getInstance().findDetails(PluginId.getId("edu.missouristate.mars"))!!.version!!
            val label = referenceMessages.getMessage("reference.version", version)
            "<p>${escapeXmlEntities(label)}</p>$body"
        }
    }

    val topics: List<MarsReferenceTopic> by lazy {
        buildList {
            for (id in listOf("start", "macros", "debug", "exceptions", "cli", "tools")) {
                val title = guides.getMessage("$id.title")
                val body = guides.getMessage("$id.body")
                add(MarsReferenceTopic(title, stripHtml(body, true)) { body })
            }
            add(about)
            add(archivedTopic("acknowledgements", "Acknowledgements.html"))
            add(archivedTopic("history", "MarsHelpHistory.html"))
            add(noticeTopic("license", "MARSlicense.txt"))
            add(noticeTopic("apache", "Apache-2.0.txt"))
            add(noticeTopic("notices", "THIRD-PARTY-NOTICES.txt"))
            for (entry in MipsDocumentationCatalog.entries) {
                add(MarsReferenceTopic(entry.name, entry.searchText, entry::referenceHtml))
            }
            for ((number, name, arguments, result) in MipsSyscallDocumentation.entries) {
                val title = referenceMessages.getMessage("reference.syscall.title", number, name)
                add(MarsReferenceTopic(title, "$arguments $result") {
                    val argumentsLabel = escapeXmlEntities(referenceMessages.getMessage("documentation.syscalls.arguments"))
                    val resultLabel = escapeXmlEntities(referenceMessages.getMessage("documentation.syscalls.result"))
                    "<p><b>$argumentsLabel</b> ${escapeXmlEntities(arguments)}</p>" +
                        "<p><b>$resultLabel</b> ${escapeXmlEntities(result)}</p>"
                })
            }
        }
    }

    private fun archivedTopic(id: String, filename: String): MarsReferenceTopic {
        val body = readResource(filename).substringAfter("<body>").substringBefore("</body>")
            .replace(Regex("</?a\\b[^>]*>", RegexOption.IGNORE_CASE), "")
        val context = escapeXmlEntities(referenceMessages.getMessage("reference.archive.note"))
        return MarsReferenceTopic(guides.getMessage("$id.title"), stripHtml(body, true)) { "<p><i>$context</i></p>$body" }
    }

    private fun noticeTopic(id: String, filename: String): MarsReferenceTopic {
        val body = readResource(filename)
        return MarsReferenceTopic(guides.getMessage("$id.title"), body) { "<pre>${escapeXmlEntities(body)}</pre>" }
    }

    private fun readResource(filename: String): String = loadText(javaClass.getResourceAsStream("/mars-reference/$filename")!!)
}
