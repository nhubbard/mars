package edu.missouristate.mars.plugin.language.syntax

import com.intellij.DynamicBundle
import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.element.SyntaxTokenTypes.WHITE_SPACE
import com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import com.intellij.platform.syntax.util.runtime.Modifiers
import com.intellij.platform.syntax.util.runtime.Parser
import com.intellij.platform.syntax.util.runtime.ParserUserState
import com.intellij.platform.syntax.util.runtime.SyntaxGeneratedParserRuntime
import com.intellij.util.text.CharArrayUtil
import com.intellij.util.text.CharSequenceSubSequence
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.BAD_CHARACTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.CHARACTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.COMMA
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.DOLLAR_WORD
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.INVALID_CHARACTER
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

/** Keep structured macro operands only when the complete grammar succeeds without recovery. */
internal fun parseCompleteMacroOperands(runtime: SyntaxGeneratedParserRuntime, level: Int, parser: Parser): Boolean {
    val start = runtime.enter_section_(level, Modifiers._NONE_)
    val complete = runtime.parseWithProtectedLastPos(level + 1, parser) && !runtime.syntaxBuilder.hasErrorsAfter(start)
    runtime.exit_section_(level, start, complete, false, null)
    return complete
}

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

/** MARS skips the definition when the physical line has fewer than three assembler tokens. */
@Suppress("UNUSED_PARAMETER")
internal fun isShortEquivalence(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    // Count the whole line only for plausible short forms. The assembler can join a sign
    // to a number and counts trailing comments and any leading label as tokens.
    val operand = builder.tokenType
    if (!operand.endsLine() && !builder.lookAhead(1).endsLine() &&
        (operand !in signs || !builder.lookAhead(2).endsLine())) return false
    val offset = builder.currentOffset
    val source = builder.text
    val start = CharArrayUtil.shiftBackwardUntil(source, offset - 1, "\r\n") + 1
    val end = CharArrayUtil.shiftForwardUntil(source, offset, "\r\n")
    return Tokenizer().tokenizeLine(1, source.subSequence(start, end).toString(), ErrorList(), false).size() < 3
}

private fun SyntaxElementType?.endsLine() = this == null || this == NEWLINE

/** The assembler's include prepass runs on the physical line before any parsing or expansion. */
@Suppress("UNUSED_PARAMETER")
internal fun isIncludeLine(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    val start = builder.currentOffset
    val source = builder.text
    return inspectMipsInclude(source, start, CharArrayUtil.shiftForwardUntil(source, start, "\r\n"))?.path() != null
}

internal fun inspectMipsInclude(source: CharSequence, start: Int, end: Int): Tokenizer.IncludePath? {
    val line = CharSequenceSubSequence(source, start, end)
    if (!line.contains(".include", ignoreCase = true)) return null
    return Tokenizer().inspectInclude(line.toString())
}

/** Report the invalid token without losing the path or resuming normal statement parsing. */
@Suppress("UNUSED_PARAMETER")
internal fun recoverBadIncludeToken(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    if (builder.tokenType != BAD_CHARACTER) return false
    val marker = builder.mark()
    builder.advanceLexer()
    marker.error(syntaxMessages.getMessage("syntax.include.invalid.token"))
    return true
}

/** Report a rejected header and leave the following line in its original scope. */
@Suppress("UNUSED_PARAMETER")
internal fun recoverInvalidMacroName(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    val message = if (builder.tokenType.endsLine()) "syntax.macro.name.missing" else "syntax.macro.name.invalid"
    builder.recoverMacroTail(message)
    return true
}

/** The rejected formal ends parameter collection, but the valid name still opens the body. */
@Suppress("UNUSED_PARAMETER")
internal fun recoverInvalidMacroFormal(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    runtime.syntaxBuilder.recoverMacroTail("syntax.macro.formal.invalid")
    return true
}

@Suppress("UNUSED_PARAMETER")
internal fun recoverMalformedMacroEnd(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    runtime.syntaxBuilder.recoverMacroTail("syntax.macro.end.trailing")
    return true
}

private fun SyntaxTreeBuilder.recoverMacroTail(messageKey: String) {
    // A labeled directive is only interpreted outside an effective macro body.
    val offset = currentOffset
    val start = CharArrayUtil.shiftBackwardUntil(text, offset - 1, "\r\n") + 1
    val prefix = Tokenizer().tokenizeLine(1, text.subSequence(start, offset).toString(), ErrorList(), false)
    if (prefix.size() < 2 || prefix[1].type != TokenTypes.COLON) {
        val message = syntaxMessages.getMessage(messageKey)
        if (tokenType.endsLine()) {
            error(message)
        } else {
            val marker = mark()
            advanceLexer()
            marker.error(message)
        }
    }
    while (!tokenType.endsLine()) advanceLexer()
}

/** Retain the broken literal and keep parsing the other operands on the line. */
@Suppress("UNUSED_PARAMETER")
internal fun recoverCharacterLiteral(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    val message = when (builder.tokenType) {
        UNCLOSED_CHARACTER -> "syntax.character.unclosed"
        INVALID_CHARACTER -> "syntax.character.invalid"
        else -> return false
    }
    val marker = builder.mark()
    builder.advanceLexer()
    marker.error(syntaxMessages.getMessage(message))
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
