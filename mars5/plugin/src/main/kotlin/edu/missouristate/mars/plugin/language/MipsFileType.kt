package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader

object MipsFileType : LanguageFileType(MipsLanguage) {
    private val bundle = DynamicBundle(MipsFileType::class.java, "messages.MarsBundle")
    private val fileIcon = IconLoader.getIcon("/icons/marsMips.svg", MipsFileType::class.java)
    override fun getName() = "MARS MIPS"
    override fun getDescription() = bundle.getMessage("filetype.description")
    override fun getDefaultExtension() = "asm"
    override fun getIcon() = fileIcon
}
