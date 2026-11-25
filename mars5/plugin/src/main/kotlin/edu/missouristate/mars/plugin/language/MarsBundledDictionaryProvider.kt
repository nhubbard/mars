package edu.missouristate.mars.plugin.language

import com.intellij.spellchecker.BundledDictionaryProvider

internal class MarsBundledDictionaryProvider : BundledDictionaryProvider {
  override fun getBundledDictionaries(): Array<String> = arrayOf("/spellchecker/mars.dic")
}
