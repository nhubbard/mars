package edu.missouristate.mars.plugin.language

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.element.SyntaxTokenTypes
import com.intellij.platform.syntax.util.lexer.LexerBase
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes
import edu.missouristate.mars.plugin.language.syntax.MipsScanner
import edu.missouristate.mars.plugin.language.syntax.MipsToken
import edu.missouristate.mars.plugin.language.syntax.MipsTokenKind
import java.util.TreeSet

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
        return when (current.kind) {
            MipsTokenKind.SPACE -> SyntaxTokenTypes.WHITE_SPACE
            MipsTokenKind.DIRECTIVE -> when (Directives.matchDirective(textOf(current))) {
                Directives.MACRO -> MipsSyntaxTypes.MACRO
                Directives.END_MACRO -> MipsSyntaxTypes.END_MACRO
                Directives.EQV -> MipsSyntaxTypes.EQV
                Directives.INCLUDE -> MipsSyntaxTypes.INCLUDE
                null -> MipsSyntaxTypes.WORD
                else -> MipsSyntaxTypes.DIRECTIVE
            }
            MipsTokenKind.REGISTER -> {
                val text = textOf(current)
                if (RegisterNames.integer(text) != null || RegisterNames.floating(text) != null) {
                    MipsSyntaxTypes.REGISTER
                } else {
                    MipsSyntaxTypes.DOLLAR_WORD
                }
            }
            MipsTokenKind.WORD -> if (textOf(current) in mnemonics) MipsSyntaxTypes.MNEMONIC else MipsSyntaxTypes.WORD
            else -> MipsTypes.syntax.getValue(current.kind.name)
        }
    }

    private fun textOf(token: MipsToken): String = buffer.subSequence(token.start, token.end).toString()

    companion object {
        private val mnemonics: Set<String> by lazy {
            InstructionDefaults.get().instructionList.mapTo(TreeSet(String.CASE_INSENSITIVE_ORDER)) { it.name }
        }
    }
}
