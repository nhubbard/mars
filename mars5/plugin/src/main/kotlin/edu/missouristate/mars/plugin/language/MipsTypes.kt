package edu.missouristate.mars.plugin.language

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.element.SyntaxTokenTypes
import com.intellij.platform.syntax.psi.ElementTypeConverterFactory
import com.intellij.platform.syntax.psi.SyntaxGrammarKitFileElementType
import com.intellij.platform.syntax.psi.convertNotNull
import com.intellij.platform.syntax.psi.elementTypeConverterOf
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes

object MipsTypes {
    val fileSyntax = SyntaxElementType("MIPS_FILE")
    val file = SyntaxGrammarKitFileElementType(MipsLanguage)
    val syntax = mapOf(
        "ADDRESS_EXPR" to MipsSyntaxTypes.ADDRESS_EXPR,
        "ADD_EXPR" to MipsSyntaxTypes.ADD_EXPR,
        "DATA_CONTINUATION" to MipsSyntaxTypes.DATA_CONTINUATION,
        "DEBUGGER_EXPRESSION" to MipsSyntaxTypes.DEBUGGER_EXPRESSION,
        "DIRECTIVE_STATEMENT" to MipsSyntaxTypes.DIRECTIVE_STATEMENT,
        "EQUIVALENCE" to MipsSyntaxTypes.EQUIVALENCE,
        "EQUALITY_EXPR" to MipsSyntaxTypes.EQUALITY_EXPR,
        "EXTERNAL_NAME" to MipsSyntaxTypes.EXTERNAL_NAME,
        "EXPR" to MipsSyntaxTypes.EXPR,
        "GROUP_EXPR" to MipsSyntaxTypes.GROUP_EXPR,
        "INCLUDE_LINE" to MipsSyntaxTypes.INCLUDE_LINE,
        "INSTRUCTION" to MipsSyntaxTypes.INSTRUCTION,
        "INVOCATION_TOKEN" to MipsSyntaxTypes.INVOCATION_TOKEN,
        "LABEL" to MipsSyntaxTypes.LABEL,
        "LITERAL_EXPR" to MipsSyntaxTypes.LITERAL_EXPR,
        "MACRO_DEFINITION" to MipsSyntaxTypes.MACRO_DEFINITION,
        "MACRO_HEADER" to MipsSyntaxTypes.MACRO_HEADER,
        "MACRO_PARAMETER" to MipsSyntaxTypes.MACRO_PARAMETER,
        "MACRO_SIGNATURE" to MipsSyntaxTypes.MACRO_SIGNATURE,
        "OPERAND" to MipsSyntaxTypes.OPERAND,
        "OPERAND_FRAGMENT" to MipsSyntaxTypes.OPERAND_FRAGMENT,
        "OPERAND_LIST" to MipsSyntaxTypes.OPERAND_LIST,
        "OPERATION" to MipsSyntaxTypes.OPERATION,
        "OPERATION_CANDIDATE" to MipsSyntaxTypes.OPERATION_CANDIDATE,
        "PARAMETER_LIST" to MipsSyntaxTypes.PARAMETER_LIST,
        "REFERENCE_EXPR" to MipsSyntaxTypes.REFERENCE_EXPR,
        "RELATIONAL_EXPR" to MipsSyntaxTypes.RELATIONAL_EXPR,
        "REPETITION" to MipsSyntaxTypes.REPETITION,
        "REPLACEMENT" to MipsSyntaxTypes.REPLACEMENT,
        "SEGMENT_ADDRESS_SYMBOL" to MipsSyntaxTypes.SEGMENT_ADDRESS_SYMBOL,
        "STATEMENT" to MipsSyntaxTypes.STATEMENT,
        "UNARY_EXPR" to MipsSyntaxTypes.UNARY_EXPR,
        "BAD_CHARACTER" to MipsSyntaxTypes.BAD_CHARACTER,
        "CHARACTER" to MipsSyntaxTypes.CHARACTER,
        "COLON" to MipsSyntaxTypes.COLON,
        "COMMA" to MipsSyntaxTypes.COMMA,
        "COMMENT" to MipsSyntaxTypes.COMMENT,
        "DIRECTIVE" to MipsSyntaxTypes.DIRECTIVE,
        "DOLLAR_WORD" to MipsSyntaxTypes.DOLLAR_WORD,
        "END_MACRO" to MipsSyntaxTypes.END_MACRO,
        "EQUALITY" to MipsSyntaxTypes.EQUALITY,
        "EQV" to MipsSyntaxTypes.EQV,
        "EXTERN" to MipsSyntaxTypes.EXTERN,
        "INCLUDE" to MipsSyntaxTypes.INCLUDE,
        "SET" to MipsSyntaxTypes.SET,
        "SEGMENT" to MipsSyntaxTypes.SEGMENT,
        "LEFT_PAREN" to MipsSyntaxTypes.LEFT_PAREN,
        "MACRO" to MipsSyntaxTypes.MACRO,
        "MINUS" to MipsSyntaxTypes.MINUS,
        "MNEMONIC" to MipsSyntaxTypes.MNEMONIC,
        "NEWLINE" to MipsSyntaxTypes.NEWLINE,
        "NUMBER" to MipsSyntaxTypes.NUMBER,
        "PARAMETER" to MipsSyntaxTypes.PARAMETER,
        "PLUS" to MipsSyntaxTypes.PLUS,
        "REGISTER" to MipsSyntaxTypes.REGISTER,
        "RELATION" to MipsSyntaxTypes.RELATION,
        "RIGHT_PAREN" to MipsSyntaxTypes.RIGHT_PAREN,
        "STRING" to MipsSyntaxTypes.STRING,
        "UNCLOSED_CHARACTER" to MipsSyntaxTypes.UNCLOSED_CHARACTER,
        "UNCLOSED_STRING" to MipsSyntaxTypes.UNCLOSED_STRING,
        "WORD" to MipsSyntaxTypes.WORD
    )
    val converter = elementTypeConverterOf(
        fileSyntax to file,
        SyntaxTokenTypes.WHITE_SPACE to TokenType.WHITE_SPACE,
        *syntax.values.map { it to IElementType(it.toString(), MipsLanguage) }.toTypedArray(),
    )
    fun psi(name: String) = converter.convertNotNull(syntax.getValue(name))
}

class MipsElementTypeConverterFactory : ElementTypeConverterFactory {
    override fun getElementTypeConverter() = MipsTypes.converter
}
