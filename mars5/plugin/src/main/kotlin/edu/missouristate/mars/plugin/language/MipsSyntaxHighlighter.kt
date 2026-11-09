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
        put(MipsTypes.psi("PARAMETER"), DefaultLanguageHighlighterColors.PARAMETER)
        put(MipsTypes.psi("COMMA"), DefaultLanguageHighlighterColors.COMMA)
        put(MipsTypes.psi("COLON"), DefaultLanguageHighlighterColors.SEMICOLON)
        for (name in listOf("WORD", "DOLLAR_WORD", "MNEMONIC")) put(MipsTypes.psi(name), DefaultLanguageHighlighterColors.IDENTIFIER)
        for (name in listOf("LEFT_PAREN", "RIGHT_PAREN")) put(MipsTypes.psi(name), DefaultLanguageHighlighterColors.PARENTHESES)
        for (name in listOf("PLUS", "MINUS", "EQUALITY", "RELATION", "BIT_OR", "BIT_XOR", "BIT_AND", "BIT_NOT", "SHIFT")) {
            put(MipsTypes.psi(name), DefaultLanguageHighlighterColors.OPERATION_SIGN)
        }
    }

    override fun getHighlightingLexer() = LexerAdapter(MipsLexer(debuggerExpression), MipsTypes.converter)
    override fun getTokenHighlights(tokenType: IElementType) = pack(colors[tokenType])
}

class MipsSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = MipsSyntaxHighlighter()
}
