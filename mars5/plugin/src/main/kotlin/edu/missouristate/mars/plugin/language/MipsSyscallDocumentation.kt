package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.documentation.DocumentationMarkup.CONTENT_END
import com.intellij.lang.documentation.DocumentationMarkup.CONTENT_START
import com.intellij.lang.documentation.DocumentationMarkup.SECTION_END
import com.intellij.lang.documentation.DocumentationMarkup.SECTION_HEADER_START
import com.intellij.lang.documentation.DocumentationMarkup.SECTION_SEPARATOR
import com.intellij.lang.documentation.DocumentationMarkup.SECTIONS_END
import com.intellij.lang.documentation.DocumentationMarkup.SECTIONS_START
import com.intellij.openapi.util.text.StringUtil

/** Offline syscall contracts shared by the editor and completion documentation. */
internal object MipsSyscallDocumentation {
    private val labels = DynamicBundle(javaClass, "messages.MarsBundle")

    val entries: List<MipsSyscallEntry> by lazy {
        val catalog = DynamicBundle.getResourceBundle(javaClass.classLoader, "messages.MarsSyscalls")
        catalog.keySet().sortedBy(String::toInt).map { key ->
            val (name, arguments, result) = catalog.getString(key).split('|', limit = 3)
            MipsSyscallEntry(key.toInt(), name, arguments, result)
        }
    }

    val html: String by lazy {
        buildString {
            append(CONTENT_START)
            append(StringUtil.escapeXmlEntities(labels.getMessage("documentation.syscalls.intro")))
            append(CONTENT_END)
            append(SECTIONS_START)
            for ((number, name, arguments, result) in entries) {
                append(SECTION_HEADER_START)
                append(number).append(' ').append(StringUtil.escapeXmlEntities(name)).append("</p>")
                append(SECTION_SEPARATOR)
                append("<b>").append(StringUtil.escapeXmlEntities(labels.getMessage("documentation.syscalls.arguments"))).append("</b> ")
                append(StringUtil.escapeXmlEntities(arguments))
                append("<br>")
                append("<b>").append(StringUtil.escapeXmlEntities(labels.getMessage("documentation.syscalls.result"))).append("</b> ")
                append(StringUtil.escapeXmlEntities(result))
                append(SECTION_END).append("</tr>")
            }
            append(SECTIONS_END)
        }
    }
}

internal data class MipsSyscallEntry(val number: Int, val name: String, val arguments: String, val result: String)
