package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.psi.tree.TokenSet

private val closedLiterals = TokenSet.create(MipsTypes.psi("STRING"), MipsTypes.psi("CHARACTER"))
private val unclosedLiterals = TokenSet.create(MipsTypes.psi("UNCLOSED_STRING"), MipsTypes.psi("UNCLOSED_CHARACTER"))

internal class MipsQuoteHandler : SimpleTokenSetQuoteHandler(TokenSet.orSet(closedLiterals, unclosedLiterals)) {
    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean =
        iterator.tokenType in closedLiterals && super.isClosingQuote(iterator, offset)

    // The lexer already accounts for MARS escape rules, including a final escaped double quote.
    override fun isNonClosedLiteral(iterator: HighlighterIterator, chars: CharSequence): Boolean =
        iterator.tokenType in unclosedLiterals
}
