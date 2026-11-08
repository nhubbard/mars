package edu.missouristate.mars.plugin.language

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.element.SyntaxTokenTypes
import com.intellij.platform.syntax.util.lexer.LexerBase
import edu.missouristate.mars.plugin.language.syntax.MipsScanner
import edu.missouristate.mars.plugin.language.syntax.MipsToken
import edu.missouristate.mars.plugin.language.syntax.MipsTokenKind

class MipsLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var limit = 0
    private var token: MipsToken? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        limit = endOffset
        token = MipsScanner.next(buffer, startOffset, endOffset)
    }

    override fun getState() = 0
    override fun getTokenStart() = token?.start ?: limit
    override fun getTokenEnd() = token?.end ?: limit
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = limit
    override fun advance() { token = MipsScanner.next(buffer, getTokenEnd(), limit) }

    override fun getTokenType(): SyntaxElementType? {
        val current = token ?: return null
        if (current.kind == MipsTokenKind.SPACE) return SyntaxTokenTypes.WHITE_SPACE
        if (current.kind == MipsTokenKind.DIRECTIVE) {
            when (buffer.subSequence(current.start, current.end).toString()) {
                ".macro" -> return MipsTypes.syntax.getValue("MACRO")
                ".end_macro" -> return MipsTypes.syntax.getValue("END_MACRO")
                ".eqv" -> return MipsTypes.syntax.getValue("EQV")
            }
        }
        return MipsTypes.syntax.getValue(current.kind.name)
    }
}
