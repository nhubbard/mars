package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType

object MipsFileType : LanguageFileType(MipsLanguage) {
    private val bundle = DynamicBundle(MipsFileType::class.java, "messages.MarsBundle")
    override fun getName() = "MARS MIPS"
    override fun getDescription() = bundle.getMessage("filetype.description")
    override fun getDefaultExtension() = "asm"
    override fun getIcon() = AllIcons.FileTypes.Custom
}
