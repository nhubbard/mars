package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.Directives
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

internal sealed interface MipsSourceEvent {
    val offset: Int

    class Header(val declaration: MipsDeclaration) : MipsSourceEvent {
        override val offset = declaration.textOffset
        private val tokens = macroLineTokens(declaration)
        private val direct = macroSignature(declaration, tokens, 0, expanded = false)

        fun bind(replacement: (String) -> String?): Begin? {
            val needsExpansion = (0 until tokens.size()).any {
                val token = tokens[it]
                token.type == TokenTypes.IDENTIFIER && replacement(token.value) != null
            }
            if (!needsExpansion) return direct
            val errors = ErrorList()
            val expanded = Tokenizer.tokenizeWithEquivalences(
                sourceLine(declaration.parent.firstChild), errors, replacement, ProgressManager::checkCanceled,
            )
            expanded.discardTrailingComment()
            if (errors.errorsOccurred()) return null
            return macroSignature(declaration, expanded, 1, expanded = true)
        }
    }

    class OperationCandidate(private val head: PsiElement) : MipsSourceEvent {
        override val offset = head.textOffset
        private val name = head.text

        fun bind(inMacro: Boolean, replacement: (String) -> String?): MipsSourceEvent? {
            if (replacement(name) == null) return null
            val errors = ErrorList()
            val tokens = Tokenizer.tokenizeWithEquivalences(
                sourceLine(head, fromLineStart = true), errors, replacement, ProgressManager::checkCanceled,
            )
            if (errors.errorsOccurred()) return null
            tokens.discardTrailingComment()
            // In an open macro a label is part of the body, even before a closing directive.
            val operation = if (!inMacro && tokens.beginsWithLabel()) 2 else 0
            if (operation >= tokens.size()) return null
            return when (Directives.matchDirective(tokens[operation].value)) {
                Directives.MACRO -> macroSignature(head, tokens, operation + 1, expanded = true)
                Directives.END_MACRO -> End(offset).takeIf { tokens.size() == operation + 1 }
                else -> null
            }
        }
    }

    data class Begin(
        val header: PsiElement, val name: String, val parameters: List<Token>, val expanded: Boolean,
    ) : MipsSourceEvent {
        override val offset = header.textOffset
        val arity: Int get() = parameters.size
    }

    data class End(override val offset: Int) : MipsSourceEvent

    data class Include(val literal: MipsStringLiteral, val path: String) : MipsSourceEvent {
        override val offset = literal.textOffset
    }

    data class DataDirective(override val offset: Int, val directive: Directives) : MipsSourceEvent

    data class Declaration(val declaration: MipsDeclaration) : MipsSourceEvent {
        override val offset = declaration.textOffset
    }
}

private val directiveStatement = MipsTypes.psi("DIRECTIVE_STATEMENT")
private val operationCandidate = MipsTypes.psi("OPERATION_CANDIDATE")
private val sourceEventTypes = setOf(
    MipsTypes.psi("MACRO_SIGNATURE"), MipsTypes.psi("END_MACRO"), MipsTypes.psi("STRING"), directiveStatement,
    MipsTypes.psi("EQUIVALENCE"), MipsTypes.psi("EXTERNAL_NAME"), operationCandidate,
)
private val dataStateDirectives = setOf(
    Directives.DATA, Directives.KDATA, Directives.TEXT, Directives.KTEXT,
    Directives.WORD, Directives.HALF, Directives.BYTE, Directives.FLOAT, Directives.DOUBLE, Directives.ASCII, Directives.ASCIIZ,
)

/** Physical source events that affect macro boundaries or the current data list. */
internal fun mipsSourceEvents(file: MipsFile): List<MipsSourceEvent> = CachedValuesManager.getCachedValue(file) {
    val paths = mipsIncludePaths(file)
    val events = buildList {
        for (element in SyntaxTraverser.psiTraverser(file).filterTypes { it in sourceEventTypes }) {
            ProgressManager.checkCanceled()
            when (element) {
                is MipsDeclaration -> if (element.kind == SymbolKind.MACRO) {
                    add(MipsSourceEvent.Header(element))
                } else {
                    add(MipsSourceEvent.Declaration(element))
                }
                is MipsStringLiteral -> paths[element.textOffset]?.let { add(MipsSourceEvent.Include(element, it)) }
                else -> when (element.node.elementType) {
                    operationCandidate -> add(MipsSourceEvent.OperationCandidate(element.firstChild))
                    directiveStatement -> {
                        val directive = Directives.matchDirective(element.firstChild.text)
                        if (directive in dataStateDirectives) add(MipsSourceEvent.DataDirective(element.textOffset, directive))
                    }
                    else -> if (isMacroEnd(element)) add(MipsSourceEvent.End(element.textOffset))
                }
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

private fun macroSignature(header: PsiElement, tokens: TokenList, nameIndex: Int, expanded: Boolean): MipsSourceEvent.Begin? {
    if (nameIndex >= tokens.size() || tokens[nameIndex].type != TokenTypes.IDENTIFIER) return null
    val arguments = (nameIndex + 1 until tokens.size()).map(tokens::get)
        .filter { it.type != TokenTypes.LEFT_PAREN && it.type != TokenTypes.RIGHT_PAREN }
    if (!arguments.all { Macro.tokenIsMacroParameter(it.value, true) }) return null
    return MipsSourceEvent.Begin(header, tokens[nameIndex].value, arguments, expanded)
}

private fun isMacroEnd(element: PsiElement): Boolean = macroLineTokens(element, fromLineStart = true).size() == 1
