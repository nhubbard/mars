package edu.missouristate.mars.cli

/** Encode fixture text as a MIPS string literal, independently of the host path separator. */
internal fun assemblyStringLiteral(value: String): String = buildString {
  append('"')
  for (character in value) {
    append(
        when (character) {
          '\\' -> "\\\\"
          '"' -> "\\\""
          '\n' -> "\\n"
          '\r' -> "\\r"
          '\t' -> "\\t"
          '\b' -> "\\b"
          '\u000c' -> "\\f"
          '\u0000' -> "\\0"
          else -> character.toString()
        },
    )
  }
  append('"')
}
