package edu.missouristate.mars.plugin.language.syntax

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.element.SyntaxTokenTypes.WHITE_SPACE
import com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import com.intellij.platform.syntax.util.runtime.ParserUserState
import com.intellij.platform.syntax.util.runtime.SyntaxGeneratedParserRuntime
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.COMMA
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.DOLLAR_WORD
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.MINUS
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.PLUS
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.WORD

internal object DebuggerExpressionParsing : ParserUserState

private val signs = setOf(PLUS, MINUS)
private val identifiers = setOf(WORD, DOLLAR_WORD)
private val spacing = setOf(WHITE_SPACE)
private val separators = spacing + COMMA

/** Honor explicit separators and the assembler's implicit boundary before a signed literal. */
@Suppress("UNUSED_PARAMETER")
internal fun continueOperand(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    if (builder.eof()) return false
    val previous = builder.previousToken(spacing)
    if (previous == COMMA) return false
    if (runtime.parserUserState === DebuggerExpressionParsing || builder.tokenType !in signs) return true

    return !builder.signAttachesToNumber(previous)
}

/** Keep a signed number together in raw macro and equivalence tokens. */
@Suppress("UNUSED_PARAMETER")
internal fun startsSignedNumber(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    return builder.tokenType in signs && builder.signAttachesToNumber(builder.previousToken(separators))
}

// MARS ignores separators when deciding whether the previous token is an identifier.
private fun SyntaxTreeBuilder.signAttachesToNumber(previous: SyntaxElementType?): Boolean =
    text.getOrNull(currentOffset + 1)?.isDigit() == true && previous !in identifiers

private fun SyntaxTreeBuilder.previousToken(ignored: Set<SyntaxElementType>): SyntaxElementType? {
    var step = -1
    var type = rawLookup(step)
    while (type in ignored) type = rawLookup(--step)
    return type
}
