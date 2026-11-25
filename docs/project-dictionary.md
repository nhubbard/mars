# Project spelling dictionary

IntelliJ loads the shared dictionary from `mars5/.idea/dictionaries/mars.xml` when
opening the `mars5` project. It uses the native `ProjectDictionaryState` format;
no plugin code or inspection suppression is required.

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
misspelled or malformed input fixtures. Those entries are project-specific accepted
tokens, rather than corrections to the source text. Existing accepted terms retain
their spelling and capitalization.

Keep shared additions sorted and unique under `<words>`. Git tracks this dictionary
while continuing to ignore personal dictionaries, workspace state and other IDE settings.
