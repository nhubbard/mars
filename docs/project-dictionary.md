# Project spelling dictionary

The MARS plugin registers `MarsBundledDictionaryProvider` with IntelliJ's public
`com.intellij.spellchecker.bundledDictionaryProvider` extension. The provider exposes
`/spellchecker/mars.dic`, packaged from
`mars5/plugin/src/main/resources/spellchecker/mars.dic`. IntelliJ loads the accepted
words in every project while MARS is enabled. The plugin
declares its dependency on the public `intellij.spellchecker` module.

For development of MARS itself, IntelliJ also loads the shared dictionary from
`mars5/.idea/dictionaries/mars.xml` when opening the `mars5` project. It uses the native
`ProjectDictionaryState` format and is available before installing a newly built plugin.

The initial word list combines the existing IntelliJ IDEA 2026.3 application-level
accepted words with every word reported by
`mars5/build/inspections/SpellCheckingInspection.xml` on November 25, 2026.
The application dictionary was read from
`~/Library/Application Support/JetBrains/IntelliJIdea2026.3/options/spellchecker-dictionary.xml`.
It remains unchanged.

The report's description supplies the checked word. Highlighted source ranges can
include escaped string syntax or underscores and do not always name that word
correctly. Other inspection reports are outside this dictionary's input scope.

The list includes instruction names, hexadecimal test data and intentionally
misspelled or malformed input fixtures. These entries allow MARS terminology and intentional test input without changing
the source strings. Existing accepted terms retain
their spelling and capitalization.

Keep the bundled `.dic` word list and the project XML dictionary in sync. The `.dic`
contains one UTF-8 word per line; the project XML stores the same sorted, unique words
under `<words>`. The provider tests check extension registration, resource loading,
Unicode, case handling, equality with the shared project dictionary and native word
lookup, including rejection of an unknown typo.

Keep shared additions sorted and unique under `<words>`. Git tracks this dictionary
while continuing to ignore personal dictionaries, workspace state and other IDE settings.
