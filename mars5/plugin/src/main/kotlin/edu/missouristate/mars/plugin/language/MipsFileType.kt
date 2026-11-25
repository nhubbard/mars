package edu.missouristate.mars.plugin.language

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import edu.missouristate.mars.plugin.dynamicBundleOf
import edu.missouristate.mars.util.javaClassOf

object MipsFileType : LanguageFileType(MipsLanguage) {
  private val bundle = dynamicBundleOf<MipsFileType>()
  // IntelliJ search caches can keep the icon after removing its file type.
  private val fileIcon =
      IconLoader.findIcon(
          javaClassOf<MipsFileType>().getResource("/icons/marsMips.svg"),
          storeToCache = false,
      )!!

  override fun getName() = "MARS MIPS"

  override fun getDescription() = bundle.getMessage("filetype.description")

  override fun getDefaultExtension() = "asm"

  override fun getIcon() = fileIcon
}
