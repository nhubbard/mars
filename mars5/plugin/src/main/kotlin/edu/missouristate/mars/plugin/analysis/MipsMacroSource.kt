package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.Token
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.psi.mipsIncludePaths

internal sealed interface MipsMacroEvent {
    val offset: Int

    data class Begin(val declaration: MipsDeclaration, val parameters: List<Token>) : MipsMacroEvent {
        override val offset = declaration.textOffset
        val arity: Int get() = parameters.size
    }

    data class End(override val offset: Int) : MipsMacroEvent

    data class Include(val literal: MipsStringLiteral, val path: String) : MipsMacroEvent {
        override val offset = literal.textOffset
    }
}

private val macroEventTypes = setOf(MipsTypes.psi("MACRO_SIGNATURE"), MipsTypes.psi("END_MACRO"), MipsTypes.psi("STRING"))

/** Physical boundary tokens only. A macro can open and close in different files after expansion. */
internal fun mipsMacroEvents(file: MipsFile): List<MipsMacroEvent> = CachedValuesManager.getCachedValue(file) {
    val paths = mipsIncludePaths(file)
    val events = buildList {
        for (element in SyntaxTraverser.psiTraverser(file).filterTypes { it in macroEventTypes }) {
            ProgressManager.checkCanceled()
            when (element) {
                is MipsDeclaration -> macroParameters(element)?.let { add(MipsMacroEvent.Begin(element, it)) }
                is MipsStringLiteral -> paths[element.textOffset]?.let { add(MipsMacroEvent.Include(element, it)) }
                else -> if (isMacroEnd(element)) add(MipsMacroEvent.End(element.textOffset))
            }
        }
    }
    CachedValueProvider.Result.create(events, file)
}

internal fun macroLineTokens(element: PsiElement, fromLineStart: Boolean = false): TokenList {
    val line = sourceLine(element, fromLineStart)
    return Tokenizer().tokenizeLine(1, line, ErrorList(), false).also(TokenList::discardTrailingComment)
}

internal fun sourceLine(element: PsiElement, fromLineStart: Boolean = false): String {
    val offset = element.textOffset
    val text = element.containingFile.viewProvider.contents
    val start = if (fromLineStart) CharArrayUtil.shiftBackwardUntil(text, offset - 1, "\r\n") + 1 else offset
    val end = CharArrayUtil.shiftForwardUntil(text, offset, "\r\n")
    return text.subSequence(start, end).toString()
}

internal fun TokenList.discardTrailingComment() {
    if (this[size() - 1].type == TokenTypes.COMMENT) remove(size() - 1)
}

private fun macroParameters(declaration: MipsDeclaration): List<Token>? {
    val tokens = macroLineTokens(declaration)
    if (tokens[0].type != TokenTypes.IDENTIFIER) return null
    val arguments = (1 until tokens.size()).map(tokens::get)
        .filter { it.type != TokenTypes.LEFT_PAREN && it.type != TokenTypes.RIGHT_PAREN }
    return arguments.takeIf { it.all { argument -> Macro.tokenIsMacroParameter(argument.value, true) } }
}

private fun isMacroEnd(element: PsiElement): Boolean = macroLineTokens(element, fromLineStart = true).size() == 1
