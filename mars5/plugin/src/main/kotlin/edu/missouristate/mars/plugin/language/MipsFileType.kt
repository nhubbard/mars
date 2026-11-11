package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader

object MipsFileType : LanguageFileType(MipsLanguage) {
    private val bundle = DynamicBundle(MipsFileType::class.java, "messages.MarsBundle")
    // IntelliJ search caches can keep the icon after removing its file type.
    private val fileIcon = IconLoader.findIcon(
        MipsFileType::class.java.getResource("/icons/marsMips.svg"), storeToCache = false,
    )!!
    override fun getName() = "MARS MIPS"
    override fun getDescription() = bundle.getMessage("filetype.description")
    override fun getDefaultExtension() = "asm"
    override fun getIcon() = fileIcon
}
