package edu.missouristate.mars.plugin.language.syntax

import edu.missouristate.mars.assembler.Tokenizer

enum class MipsTokenKind {
    SPACE, NEWLINE, COMMENT, WORD, DIRECTIVE, REGISTER, PARAMETER, NUMBER,
    STRING, CHARACTER, UNCLOSED_STRING, UNCLOSED_CHARACTER, INVALID_CHARACTER,
    COMMA, COLON, LEFT_PAREN, RIGHT_PAREN, PLUS, MINUS, EQUALITY, RELATION,
    BIT_OR, BIT_XOR, BIT_AND, BIT_NOT, SHIFT, BAD_CHARACTER,
}

data class MipsToken(val kind: MipsTokenKind, val start: Int, val end: Int)

/** Lossless file-local scanning. Every token ends at a restartable boundary; strings end at EOL. */
object MipsScanner {
    private const val characterDelimiters = " \t,:()#\"+-"
    private const val assemblyDelimiters = " \t\r\n,:()#\"'"

    fun scan(source: CharSequence): List<MipsToken> = buildList {
        var position = 0
        while (position < source.length) {
            val token = next(source, position, source.length)!!
            add(token)
            position = token.end
        }
    }

    fun next(source: CharSequence, start: Int, end: Int = source.length, debuggerExpression: Boolean = false): MipsToken? {
        require(start >= 0 && start <= end && end <= source.length) { "Invalid scanning range" }
        if (start == end) return null
        val first = source[start]
        var position = start + 1
        val kind = when {
            first == '\r' || first == '\n' -> {
                if (first == '\r' && position < end && source[position] == '\n') position++
                MipsTokenKind.NEWLINE
            }
            first.isWhitespace() -> {
                while (position < end && source[position].isWhitespace() && source[position] != '\r' && source[position] != '\n') position++
                MipsTokenKind.SPACE
            }
            first == '#' -> {
                while (position < end && source[position] != '\r' && source[position] != '\n') position++
                MipsTokenKind.COMMENT
            }
            first == '"' -> {
                while (position < end && source[position] != '\r' && source[position] != '\n') {
                    // MARS tests the immediately preceding character, even after two backslashes.
                    val closing = source[position] == '"' && source[position - 1] != '\\'
                    position++
                    if (closing) break
                }
                // At the end of the line, the assembler also accepts a final escaped quote.
                if (position > start + 1 && source[position - 1] == '"') MipsTokenKind.STRING else MipsTokenKind.UNCLOSED_STRING
            }
            first == '\'' -> {
                var closed = false
                while (position < end && source[position] != '\r' && source[position] != '\n') {
                    val character = source[position]
                    // A separator can itself be the character, as in ',' or '#'.
                    if (character in characterDelimiters &&
                        !(position == start + 1 && position + 1 < end && source[position + 1] == '\'')) {
                        break
                    }
                    position++
                    if (character == '\'') {
                        closed = true
                        break
                    }
                    if (character == '\\' && position < end && source[position] !in "\r\n") {
                        // A broken escape can end at a separator, leaving the next operand intact.
                        if (source[position] in " \t,:()#" &&
                            (position + 1 == end || source[position + 1] != '\'')) break
                        position++
                    }
                }
                when {
                    !closed -> MipsTokenKind.UNCLOSED_CHARACTER
                    Tokenizer.decodeCharacterLiteral(source.subSequence(start, position)) < 0 -> MipsTokenKind.INVALID_CHARACTER
                    else -> MipsTokenKind.CHARACTER
                }
            }
            first in ",:()+-" -> when (first) {
                ',' -> MipsTokenKind.COMMA
                ':' -> MipsTokenKind.COLON
                '(' -> MipsTokenKind.LEFT_PAREN
                ')' -> MipsTokenKind.RIGHT_PAREN
                '+' -> MipsTokenKind.PLUS
                else -> MipsTokenKind.MINUS
            }
            debuggerExpression && first in "|^&~" -> when (first) {
                '|' -> MipsTokenKind.BIT_OR
                '^' -> MipsTokenKind.BIT_XOR
                '&' -> MipsTokenKind.BIT_AND
                else -> MipsTokenKind.BIT_NOT
            }
            debuggerExpression && first in "<>" && position < end && source[position] == first -> {
                position++
                if (first == '>' && position < end && source[position] == '>') position++
                MipsTokenKind.SHIFT
            }
            debuggerExpression && first in "<>=!" -> {
                val hasEquals = position < end && source[position] == '='
                if (hasEquals) position++
                when {
                    first in "<>" -> MipsTokenKind.RELATION
                    hasEquals -> MipsTokenKind.EQUALITY
                    else -> MipsTokenKind.BAD_CHARACTER
                }
            }
            first == '%' -> {
                // MARS accepts any percent-prefixed token as a parameter name.
                while (position < end) {
                    val character = source[position]
                    if (character in assemblyDelimiters ||
                        character in "+-" && !continuesSignedExponent(source, position, end)) break
                    position++
                }
                MipsTokenKind.PARAMETER
            }
            first.isNameCharacter() || !debuggerExpression -> {
                val numeric = first.isDigit() || (first == '.' && position < end && source[position].isDigit())
                val exponentMarkers = when {
                    !debuggerExpression -> "eE"
                    !numeric -> ""
                    first == '0' && position < end && source[position] in "xX" -> "pP"
                    else -> "eE"
                }
                var signedExponent = false
                var invalidCharacter = !first.isNameCharacter()
                while (position < end) {
                    val character = source[position]
                    if (character.isNameCharacter() || debuggerExpression && character == '%') {
                        position++
                    } else if (character in "+-" && continuesSignedExponent(source, position, end, exponentMarkers)) {
                        signedExponent = true
                        position++
                    } else if (!debuggerExpression && character !in assemblyDelimiters && character !in "+-") {
                        // MARS classifies the whole candidate, even when it contains illegal characters.
                        invalidCharacter = true
                        position++
                    } else break
                }
                when {
                    // A floating-point value can contain a signed exponent or trailing ASCII control whitespace.
                    invalidCharacter || signedExponent && !debuggerExpression -> {
                        val value = source.subSequence(start, position).toString().toDoubleOrNull()
                        if (value == null) MipsTokenKind.BAD_CHARACTER else MipsTokenKind.NUMBER
                    }
                    // A leading dot also starts identifiers. Only a complete decimal takes priority.
                    first == '.' -> if (numeric && source.subSequence(start, position).toString().toDoubleOrNull() != null) {
                        MipsTokenKind.NUMBER
                    } else {
                        MipsTokenKind.DIRECTIVE
                    }
                    numeric -> MipsTokenKind.NUMBER
                    first == '$' -> MipsTokenKind.REGISTER
                    else -> MipsTokenKind.WORD
                }
            }
            else -> MipsTokenKind.BAD_CHARACTER
        }
        return MipsToken(kind, start, position)
    }

    private fun Char.isNameCharacter(): Boolean = isLetterOrDigit() || this in "_.$"

    private fun continuesSignedExponent(source: CharSequence, position: Int, end: Int, markers: String = "eE"): Boolean =
        source[position - 1] in markers && position + 1 < end && source[position + 1].isDigit()
}
