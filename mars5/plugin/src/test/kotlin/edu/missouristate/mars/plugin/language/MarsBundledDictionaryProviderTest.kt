package edu.missouristate.mars.plugin.language

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.JDOMUtil
import com.intellij.spellchecker.BundledDictionaryProvider
import com.intellij.spellchecker.dictionary.Dictionary.LookupStatus.Alien
import com.intellij.spellchecker.dictionary.Dictionary.LookupStatus.Present
import com.intellij.spellchecker.dictionary.UserDictionary
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("UnresolvedPluginConfigReference")
@TestApplication
class MarsBundledDictionaryProviderTest {
  @Test
  fun `registered provider exposes a sorted UTF-8 dictionary from its own classloader`() {
    val providers =
        ExtensionPointName.create<BundledDictionaryProvider>(
                "com.intellij.spellchecker.bundledDictionaryProvider",
            )
            .extensionList
    val provider = providers.filterIsInstance<MarsBundledDictionaryProvider>().single()
    assertEquals(listOf("/spellchecker/mars.dic"), provider.getBundledDictionaries().toList())
    val words = bundledWords(provider)
    assertEquals(words.distinct().sorted(), words)
    assertTrue(words.all { it.isNotBlank() && it.none(Char::isWhitespace) })
    assertTrue(
        words.containsAll(
            listOf("backstep", "MSACSR", "mips", "frsqrt", "mAcRo", "Wätzig", "xabcd"),
        ),
    )
  }

  @Test
  fun `native dictionary lookup accepts every bundled entry without accepting arbitrary typos`() {
    val words = bundledWords(MarsBundledDictionaryProvider())
    val dictionary = UserDictionary("mars", words)
    for (word in words) assertEquals(Present, dictionary.lookup(word), word)
    assertEquals(Present, dictionary.lookup("MIPS"))
    assertEquals(Alien, dictionary.lookup("marsunrecognizedspellingcontrol"))
  }

  @Test
  fun `bundled words stay synchronized with the shared project dictionary`() {
    val projectWords =
        checkNotNull(javaClass.getResourceAsStream("/project-dictionary/mars.xml"))
            .use { JDOMUtil.load(it) }
            .getChild("dictionary")
            .getChild("words")
            .getChildren("w")
            .map { it.text }
    assertEquals(projectWords, bundledWords(MarsBundledDictionaryProvider()))
  }

  private fun bundledWords(provider: MarsBundledDictionaryProvider): List<String> =
      checkNotNull(
              provider.javaClass.getResourceAsStream(provider.getBundledDictionaries().single()),
          )
          .bufferedReader(Charsets.UTF_8)
          .use { it.readLines() }
}
