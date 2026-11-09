package edu.missouristate.mars.plugin.language

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.syntax.psi.lexer.LexerAdapter
import com.intellij.psi.tree.IElementType

internal val punctuationColor = TextAttributesKey.createTextAttributesKey(
    "MARS_MIPS_PUNCTUATION", DefaultLanguageHighlighterColors.OPERATION_SIGN,
)
internal val macroParameterColor = TextAttributesKey.createTextAttributesKey(
    "MARS_MIPS_MACRO_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER,
)

class MipsSyntaxHighlighter(private val debuggerExpression: Boolean = false) : SyntaxHighlighterBase() {
    private val colors = buildMap<IElementType, TextAttributesKey> {
        for (name in listOf("DIRECTIVE", "MACRO", "END_MACRO", "EQV", "INCLUDE", "SET", "SEGMENT", "EXTERN")) {
            put(MipsTypes.psi(name), DefaultLanguageHighlighterColors.KEYWORD)
        }
        for (name in listOf("STRING", "UNCLOSED_STRING", "CHARACTER")) put(MipsTypes.psi(name), DefaultLanguageHighlighterColors.STRING)
        for (name in listOf("UNCLOSED_CHARACTER", "BAD_CHARACTER")) put(MipsTypes.psi(name), HighlighterColors.BAD_CHARACTER)
        put(MipsTypes.psi("COMMENT"), DefaultLanguageHighlighterColors.LINE_COMMENT)
        put(MipsTypes.psi("NUMBER"), DefaultLanguageHighlighterColors.NUMBER)
        put(MipsTypes.psi("REGISTER"), DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        put(MipsTypes.psi("PARAMETER"), macroParameterColor)
        for (name in listOf("WORD", "DOLLAR_WORD", "MNEMONIC")) put(MipsTypes.psi(name), DefaultLanguageHighlighterColors.IDENTIFIER)
        for (name in listOf("COMMA", "COLON", "LEFT_PAREN", "RIGHT_PAREN", "PLUS", "MINUS")) {
            put(MipsTypes.psi(name), punctuationColor)
        }
        for (name in listOf("EQUALITY", "RELATION", "BIT_OR", "BIT_XOR", "BIT_AND", "BIT_NOT", "SHIFT")) {
            put(MipsTypes.psi(name), DefaultLanguageHighlighterColors.OPERATION_SIGN)
        }
    }

    override fun getHighlightingLexer() = LexerAdapter(MipsLexer(debuggerExpression), MipsTypes.converter)
    override fun getTokenHighlights(tokenType: IElementType) = pack(colors[tokenType])
}

class MipsSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = MipsSyntaxHighlighter()
}
