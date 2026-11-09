package edu.missouristate.mars.plugin.language.syntax

import com.intellij.DynamicBundle
import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.element.SyntaxTokenTypes.WHITE_SPACE
import com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import com.intellij.platform.syntax.util.runtime.ParserUserState
import com.intellij.platform.syntax.util.runtime.SyntaxGeneratedParserRuntime
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.CHARACTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.COMMA
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.DOLLAR_WORD
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.LEFT_PAREN
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.MINUS
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.MNEMONIC
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.NEWLINE
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.NUMBER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.PARAMETER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.PLUS
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.REGISTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.UNCLOSED_CHARACTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.WORD

internal object DebuggerExpressionParsing : ParserUserState

private val signs = setOf(PLUS, MINUS)
private val identifiers = setOf(WORD, DOLLAR_WORD)
private val addressLabels = identifiers + MNEMONIC
private val addressOffsets = setOf(NUMBER, CHARACTER, WORD, MNEMONIC, DOLLAR_WORD, PARAMETER)
private val addressBases = setOf(REGISTER, WORD, DOLLAR_WORD, PARAMETER)
private val spacing = setOf(WHITE_SPACE)
private val separators = spacing + COMMA
private val syntaxMessages = DynamicBundle(MipsScanner::class.java, "messages.MarsBundle")

/** Apply the assembler's boundaries for address and signed operands. */
@Suppress("UNUSED_PARAMETER")
internal fun continueOperand(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    if (builder.eof()) return false
    if (runtime.parserUserState === DebuggerExpressionParsing) return true
    val previous = builder.previousToken(spacing)
    if (builder.tokenType == LEFT_PAREN) {
        // The assembler discards commas in an offset(base) operand. A register before
        // the opening parenthesis is a separate operand even without a comma.
        if (previous != COMMA) return previous != REGISTER
        return builder.lookAhead(1) in addressBases && builder.previousToken(separators) in addressOffsets
    }
    if (previous == COMMA) {
        // Label+offset forms accept commas around the plus. A mnemonic can name a label,
        // but a plus joined to digits after a mnemonic is still a signed literal.
        val label = builder.previousToken(separators)
        return builder.tokenType == PLUS && label in addressLabels && !builder.signAttachesToNumber(label)
    }
    if (builder.tokenType !in signs) return true

    return !builder.signAttachesToNumber(previous)
}

/** Keep a signed number together in raw macro and equivalence tokens. */
@Suppress("UNUSED_PARAMETER")
internal fun startsSignedNumber(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    return builder.tokenType in signs && builder.signAttachesToNumber(builder.previousToken(separators))
}

/** Retain the broken literal and keep parsing the other operands on the line. */
@Suppress("UNUSED_PARAMETER")
internal fun recoverCharacterLiteral(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    if (builder.tokenType != UNCLOSED_CHARACTER) return false
    val marker = builder.mark()
    builder.advanceLexer()
    marker.error(syntaxMessages.getMessage("syntax.character.unclosed"))
    return true
}

/** Discard a malformed statement head through the physical end of its line. */
@Suppress("UNUSED_PARAMETER")
internal fun recoverStatement(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    if (builder.eof() || builder.tokenType == NEWLINE) return false
    val marker = builder.mark()
    do {
        builder.advanceLexer()
    } while (!builder.eof() && builder.tokenType != NEWLINE)
    marker.error(syntaxMessages.getMessage("syntax.statement.unexpected"))
    return true
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
