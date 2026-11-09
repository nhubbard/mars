package edu.missouristate.mars.plugin.language.syntax

import com.intellij.platform.syntax.element.SyntaxTokenTypes.WHITE_SPACE
import com.intellij.platform.syntax.util.runtime.SyntaxGeneratedParserRuntime
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.COMMA

/** Explicit separators keep adjacent operands from becoming one infix or postfix expression. */
@Suppress("UNUSED_PARAMETER")
internal fun continueOperand(runtime: SyntaxGeneratedParserRuntime, level: Int): Boolean {
    val builder = runtime.syntaxBuilder
    if (builder.eof()) return false
    var step = -1
    while (builder.rawLookup(step) == WHITE_SPACE) step--
    return builder.rawLookup(step) != COMMA
}
