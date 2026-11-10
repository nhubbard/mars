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
    val html: String by lazy {
        val catalog = DynamicBundle.getResourceBundle(javaClass.classLoader, "messages.MarsSyscalls")
        val labels = DynamicBundle(javaClass, "messages.MarsBundle")
        buildString {
            append(CONTENT_START)
            append(StringUtil.escapeXmlEntities(labels.getMessage("documentation.syscalls.intro")))
            append(CONTENT_END)
            append(SECTIONS_START)
            for (key in catalog.keySet().sortedBy(String::toInt)) {
                val (name, arguments, result) = catalog.getString(key).split('|', limit = 3)
                append(SECTION_HEADER_START)
                append(key).append(' ').append(StringUtil.escapeXmlEntities(name)).append("</p>")
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
