package edu.missouristate.mars.plugin.language.syntax

import com.intellij.platform.syntax.element.SyntaxTokenTypes.WHITE_SPACE
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

/** Honor explicit separators and the assembler's implicit boundary before a signed literal. */
@Suppress("UNUSED_PARAMETER")
internal fun continueOperand(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    if (builder.eof()) return false
    var step = -1
    while (builder.rawLookup(step) == WHITE_SPACE) step--
    val previous = builder.rawLookup(step)
    if (previous == COMMA) return false
    if (runtime.parserUserState === DebuggerExpressionParsing || builder.tokenType !in signs) return true

    // The Java tokenizer attaches a sign to the following digit unless the preceding token
    // is an identifier. Keep the sign as a PSI token for styling and unary expressions.
    return builder.text.getOrNull(builder.currentOffset + 1)?.isDigit() != true || previous in identifiers
}
