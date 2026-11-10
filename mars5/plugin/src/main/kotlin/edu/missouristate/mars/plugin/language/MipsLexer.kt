package edu.missouristate.mars.plugin.language

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.element.SyntaxTokenTypes
import com.intellij.platform.syntax.util.lexer.LexerBase
import com.intellij.util.text.CharArrayUtil
import com.intellij.util.text.CharSequenceSubSequence
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes
import edu.missouristate.mars.plugin.language.syntax.MipsScanner
import edu.missouristate.mars.plugin.language.syntax.MipsToken
import edu.missouristate.mars.plugin.language.syntax.MipsTokenKind
import edu.missouristate.mars.plugin.language.syntax.findMipsInclude
import java.util.TreeSet

class MipsLexer(private val debuggerExpression: Boolean = false) : LexerBase() {
    private var buffer: CharSequence = ""
    private var limit = 0
    private var token: MipsToken? = null
    private var includeLineEnd = -1
    private var includeDirective = -1

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        limit = endOffset
        includeLineEnd = -1
        token = scanToken(startOffset)
    }

    // Include recovery can change a token before the edit. Let the highlighter restart at the line boundary.
    override fun getState(): Int {
        val offset = getTokenStart()
        return if (debuggerExpression || offset == 0 || buffer[offset - 1] in "\r\n") 0 else 1
    }

    override fun getTokenStart() = token?.start ?: limit
    override fun getTokenEnd() = token?.end ?: limit
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = limit
    override fun advance() { token = scanToken(getTokenEnd()) }

    private fun scanToken(offset: Int): MipsToken? {
        val scanned = MipsScanner.next(buffer, offset, limit, debuggerExpression) ?: return null
        if (debuggerExpression || scanned.end - offset <= includeName.length) return scanned
        // The normal scanner handles an include starting at a token boundary.
        val interior = CharSequenceSubSequence(buffer, offset + 1, scanned.end)
        if (!interior.contains(includeName, ignoreCase = true)) return scanned

        if (offset >= includeLineEnd) {
            val start = CharArrayUtil.shiftBackwardUntil(buffer, offset - 1, "\r\n") + 1
            includeLineEnd = minOf(limit, CharArrayUtil.shiftForwardUntil(buffer, offset, "\r\n"))
            val include = findMipsInclude(buffer, start, includeLineEnd)
            includeDirective = if (include == null) -1 else start + include.directiveOffset()
        }
        // A malformed token can swallow the directive and path. Resume at the assembler's directive.
        if (includeDirective !in (offset + 1) until scanned.end) return scanned
        return MipsToken(MipsTokenKind.BAD_CHARACTER, offset, includeDirective)
    }

    override fun getTokenType(): SyntaxElementType? {
        val current = token ?: return null
        if (debuggerExpression && current.kind in invalidDebuggerTokens) {
            return MipsSyntaxTypes.BAD_CHARACTER
        }
        return when (current.kind) {
            MipsTokenKind.SPACE -> SyntaxTokenTypes.WHITE_SPACE
            MipsTokenKind.DIRECTIVE -> when (Directives.matchDirective(textOf(current))) {
                Directives.MACRO -> MipsSyntaxTypes.MACRO
                Directives.END_MACRO -> MipsSyntaxTypes.END_MACRO
                Directives.EQV -> MipsSyntaxTypes.EQV
                Directives.INCLUDE -> MipsSyntaxTypes.INCLUDE
                Directives.SET -> MipsSyntaxTypes.SET
                Directives.EXTERN -> MipsSyntaxTypes.EXTERN
                Directives.DATA, Directives.KDATA, Directives.TEXT, Directives.KTEXT -> MipsSyntaxTypes.SEGMENT
                null -> MipsSyntaxTypes.WORD
                else -> MipsSyntaxTypes.DIRECTIVE
            }
            MipsTokenKind.REGISTER -> if (Macro.tokenIsMacroParameter(textOf(current), true)) {
                MipsSyntaxTypes.DOLLAR_WORD
            } else {
                MipsSyntaxTypes.REGISTER
            }
            MipsTokenKind.WORD -> {
                val text = textOf(current)
                when {
                    text == "NaN" || text == "Infinity" -> MipsSyntaxTypes.NUMBER
                    text in mnemonics -> MipsSyntaxTypes.MNEMONIC
                    else -> MipsSyntaxTypes.WORD
                }
            }
            else -> MipsTypes.syntax.getValue(current.kind.name)
        }
    }

    private fun textOf(token: MipsToken): String = buffer.subSequence(token.start, token.end).toString()

    companion object {
        private val includeName = Directives.INCLUDE.getName()
        private val invalidDebuggerTokens = setOf(MipsTokenKind.COMMA, MipsTokenKind.COMMENT, MipsTokenKind.UNCLOSED_STRING)
        private val mnemonics: Set<String> by lazy {
            InstructionDefaults.get().instructionList.mapTo(TreeSet(String.CASE_INSENSITIVE_ORDER)) { it.name }
        }
    }
}
