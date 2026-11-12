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
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.BAD_CHARACTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.CHARACTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.COLON
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.COMMA
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.DOLLAR_WORD
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.END_MACRO
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.INVALID_CHARACTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.LEFT_PAREN
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.MACRO
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.MINUS
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.MNEMONIC
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.NEWLINE
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.NUMBER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.PARAMETER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.PLUS
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.REGISTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.STRING
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.UNCLOSED_CHARACTER
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.WORD
import java.util.TreeSet

internal object DebuggerExpressionParsing : ParserUserState

private val signs = setOf(PLUS, MINUS)
private val repeatPrefixes = signs + setOf(NUMBER, CHARACTER, STRING)
private val identifiers = setOf(WORD, DOLLAR_WORD)
private val labelTokens = identifiers + setOf(MNEMONIC, PARAMETER)
private val addressOffsets = setOf(NUMBER, CHARACTER, WORD, MNEMONIC, DOLLAR_WORD, PARAMETER, BAD_CHARACTER)
private val addressBases = setOf(REGISTER, WORD, DOLLAR_WORD, PARAMETER)
private val registerAliases = identifiers + PARAMETER
private val spacing = setOf(WHITE_SPACE)
private val separators = spacing + COMMA
private val unfinishedIncludeRecoveryHeads = setOf(MACRO, END_MACRO, BAD_CHARACTER, UNCLOSED_CHARACTER, INVALID_CHARACTER)
private val syntaxMessages = DynamicBundle(MipsScanner::class.java, "messages.MarsBundle")
private val labelOffsetMnemonics by lazy {
    InstructionDefaults.get().instructionList.asSequence()
        .filter { it.tokenList.size() > 3 && it.tokenList[3].type == TokenTypes.PLUS }
        .mapTo(TreeSet(String.CASE_INSENSITIVE_ORDER)) { it.name }
}

/** Prefer raw substitution tokens when a complete structured operand list needs recovery. */
internal fun parseCompleteOperands(runtime: SyntaxGeneratedParserRuntime, level: Int, parser: Parser): Boolean {
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
        if (builder.isAfterRegisterAlias()) return false
        if (previous != COMMA) return previous != REGISTER
        return builder.lookAhead(1) in addressBases && builder.previousToken(separators) in addressOffsets
    }
    val left = if (previous == COMMA) builder.previousToken(separators) else previous
    // The assembler accepts commas around the plus in a label offset.
    if (previous == COMMA && (builder.tokenType != PLUS || left !in labelTokens)) return false
    if (builder.tokenType !in signs || !builder.signTouchesNumber()) return true
    // An identifier can become a register or value during substitution. An adjacent sign
    // belongs to the following number unless a written instruction permits label + offset here.
    return builder.tokenType == PLUS && left in registerAliases && builder.isAfterLabelOperand()
}

/** Keep a signed number together in raw macro and equivalence tokens. */
@Suppress("UNUSED_PARAMETER")
internal fun startsSignedNumber(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    return builder.tokenType in signs && builder.signAttachesToNumber(builder.previousToken(separators))
}

/** A literal or detached sign can occupy the ignored first token of a continued numeric repetition. */
@Suppress("UNUSED_PARAMETER")
internal fun isDataRepeatPrefix(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    if (builder.tokenType !in repeatPrefixes) return false
    val detachedSign = builder.tokenType in signs && !builder.signTouchesNumber()
    // Each assembler number can occupy two lexer leaves, including its sign.
    if (!detachedSign && (2..4).none { builder.lookAhead(it) == COLON }) return false
    val start = builder.currentOffset
    val source = builder.text
    val end = CharArrayUtil.shiftForwardUntil(source, start, "\r\n")
    val tokens = Tokenizer().tokenizeLine(1, source.subSequence(start, end).toString(), ErrorList(), false)
    if (tokens.hasNumericRepetitionShape()) return true
    if (!detachedSign) return false
    val firstSubstitutable = (1 until tokens.size()).firstOrNull {
        val type = tokens[it].type
        type == TokenTypes.IDENTIFIER || type == TokenTypes.MACRO_PARAMETER
    } ?: return false
    // Only a substitution immediately after the sign could turn it into part of the value.
    return firstSubstitutable > 1 || tokens[1].startPos > 2
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

/** Use the prepass's line, or recover an unfinished path before a malformed prefix consumes it. */
@Suppress("UNUSED_PARAMETER")
internal fun isIncludeLine(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    val start = builder.currentOffset
    val source = builder.text
    val include = inspectMipsInclude(source, start, CharArrayUtil.shiftForwardUntil(source, start, "\r\n")) ?: return false
    if (include.path() != null || builder.tokenType in unfinishedIncludeRecoveryHeads) return true
    // Like the statement rule, look past at most one leading label.
    return builder.tokenType in labelTokens && builder.lookAhead(1) == COLON &&
        builder.lookAhead(2) in unfinishedIncludeRecoveryHeads
}

internal fun inspectMipsInclude(source: CharSequence, start: Int, end: Int): Tokenizer.IncludePath? {
    val line = CharSequenceSubSequence(source, start, end)
    if (!line.contains(".include", ignoreCase = true)) return null
    return Tokenizer().inspectInclude(line.toString())
}

/** Report the invalid token without losing the path or resuming normal statement parsing. */
@Suppress("UNUSED_PARAMETER")
internal fun recoverBadIncludeToken(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean =
    runtime.syntaxBuilder.recoverBadToken("syntax.include.invalid.token")

/** Keep the following operand or replacement token available while the bad token is being edited. */
@Suppress("UNUSED_PARAMETER")
internal fun recoverBadOperandToken(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean =
    runtime.syntaxBuilder.recoverBadToken("syntax.operand.invalid.token")

internal fun recoverInvalidOperandExpression(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean =
    runtime.parserUserState !== DebuggerExpressionParsing && recoverBadOperandToken(runtime, level)

private fun SyntaxTreeBuilder.recoverBadToken(messageKey: String): Boolean {
    val signed = tokenType in signs && lookAhead(1) == BAD_CHARACTER &&
        signAttachesToNumber(previousToken(separators))
    if (!signed && tokenType != BAD_CHARACTER) return false
    val marker = mark()
    if (signed) advanceLexer()
    advanceLexer()
    marker.error(syntaxMessages.getMessage(messageKey))
    return true
}

/** Report a rejected header and leave the following line in its original scope. */
@Suppress("UNUSED_PARAMETER")
internal fun recoverMissingMacroName(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    if (builder.tokenType != MACRO || !builder.lookAhead(1).endsLine()) return false
    val directive = builder.mark()
    val followsLabel = builder.previousToken(separators) == COLON
    builder.advanceLexer()
    // The timeline decides whether a label is stripped before interpreting this directive.
    if (followsLabel) directive.drop() else directive.error(syntaxMessages.getMessage("syntax.macro.name.missing"))
    return true
}

@Suppress("UNUSED_PARAMETER")
internal fun recoverInvalidMacroName(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    runtime.syntaxBuilder.recoverMacroTail("syntax.macro.name.invalid")
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
        val marker = mark()
        if (tokenType in signs && signAttachesToNumber(previousToken(separators))) advanceLexer()
        advanceLexer()
        marker.error(message)
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
    signTouchesNumber() && previous !in identifiers

private fun SyntaxTreeBuilder.signTouchesNumber(): Boolean = text.getOrNull(currentOffset + 1)?.isDigit() == true

/** MARS address forms begin with a register, which may be written as an alias or macro formal. */
private fun SyntaxTreeBuilder.isAfterRegisterAlias(): Boolean {
    val operand = previousTokenIndex(-1, separators)
    if (rawLookup(operand) !in registerAliases) return false
    return isInstructionHead(previousTokenIndex(operand - 1, separators))
}

private fun SyntaxTreeBuilder.isAfterLabelOperand(): Boolean {
    val label = previousTokenIndex(-1, separators)
    val firstOperand = previousTokenIndex(label - 1, separators)
    val operation = previousTokenIndex(firstOperand - 1, separators)
    if (!isInstructionHead(operation)) return false
    return text.subSequence(rawTokenTypeStart(operation), rawTokenTypeStart(operation + 1)).toString() in labelOffsetMnemonics
}

private fun SyntaxTreeBuilder.isInstructionHead(operation: Int): Boolean {
    if (rawLookup(operation) != MNEMONIC) return false
    val prefix = rawLookup(previousTokenIndex(operation - 1, separators))
    return prefix.endsLine() || prefix == COLON
}

private fun SyntaxTreeBuilder.previousToken(ignored: Set<SyntaxElementType>): SyntaxElementType? =
    rawLookup(previousTokenIndex(-1, ignored))

private fun SyntaxTreeBuilder.previousTokenIndex(from: Int, ignored: Set<SyntaxElementType>): Int {
    var step = from
    var type = rawLookup(step)
    while (type in ignored) type = rawLookup(--step)
    return step
}
