package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
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
import edu.missouristate.mars.plugin.language.operationHead
import edu.missouristate.mars.plugin.language.operationOwners
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.psi.mipsIncludePaths
import edu.missouristate.mars.util.Binary

internal sealed interface MipsSourceEvent {
    val offset: Int

    sealed interface Effective : MipsSourceEvent

    class Header(val declaration: MipsDeclaration) : MipsSourceEvent {
        override val offset = declaration.textOffset
        private val tokens = macroLineTokens(declaration)
        private val direct = macroSignature(declaration, tokens, 0, offset)

        fun bind(replacement: (String) -> String?): Begin? {
            val needsExpansion = (0 until tokens.size()).any {
                val token = tokens[it]
                token.type == TokenTypes.IDENTIFIER && replacement(token.value) != null
            }
            if (!needsExpansion) return direct
            val errors = ErrorList()
            val directive = declaration.parent.firstChild
            val expanded = Tokenizer.tokenizeMappedLine(
                sourceLine(directive), errors, replacement, ProgressManager::checkCanceled,
            )
            expanded.tokens().discardTrailingComment()
            if (errors.errorsOccurred()) return null
            return macroSignature(declaration, expanded.tokens(), 1, directive.textOffset, expanded)
        }
    }

    class OperationCandidate(owner: PsiElement) : MipsSourceEvent {
        private val head = owner.operationHead
        override val offset = head.textOffset
        private val followsLabel = owner.parent.node.findChildByType(labelType) != null
        private val thirdToken = if (owner.elementType == operationCandidateType && !followsLabel) {
            lazy(LazyThreadSafetyMode.PUBLICATION) {
                val tokens = macroLineTokens(head)
                if (tokens.size() >= 3) tokens[2] else null
            }
        } else null

        fun bind(inMacro: Boolean, replacement: (String) -> String?, addLabel: (String, PsiElement) -> Unit): Effective? {
            if (!hasLeadingEquivalence(head, replacement) && !canDefineFromOperand(replacement)) return null
            val errors = ErrorList()
            val expanded = Tokenizer.tokenizeMappedLine(
                sourceLine(head, fromLineStart = true), errors, replacement, ProgressManager::checkCanceled,
            )
            val tokens = expanded.tokens()
            val startsWithLabel = tokens.beginsWithLabel()
            if (startsWithLabel && !followsLabel) addLabel(tokens[0].value, head)
            if (errors.errorsOccurred()) return null
            tokens.discardTrailingComment()
            expanded.expanded().definition()?.let {
                val directiveOffset = sourceLineStart(head) + expanded.equivalenceDirectiveOffset()
                val site = head.containingFile.findElementAt(directiveOffset)!!
                return Equivalence(site, it.name(), it.replacement())
            }
            // In an open macro a label is part of the body, even before a closing directive.
            val operation = if (!inMacro && startsWithLabel) 2 else 0
            if (operation >= tokens.size()) return null
            val directive = Directives.matchDirective(tokens[operation].value)
            return when (directive) {
                Directives.MACRO -> macroSignature(head, tokens, operation + 1, sourceLineStart(head), expanded)
                Directives.END_MACRO -> End(offset).takeIf { tokens.size() == operation + 1 }
                Directives.EXTERN -> externalReservation(head, tokens, operation)
                in dataStateDirectives -> DataDirective(offset, directive)
                else -> null
            }
        }

        private fun canDefineFromOperand(replacement: (String) -> String?): Boolean {
            val token = thirdToken?.value ?: return false
            return when (token.type) {
                TokenTypes.DIRECTIVE -> Directives.matchDirective(token.value) == Directives.EQV
                TokenTypes.IDENTIFIER -> replacement(token.value) != null
                else -> false
            }
        }
    }

    class LabelDeclaration(val declaration: MipsDeclaration) : MipsSourceEvent {
        override val offset = declaration.textOffset
        private val name = declaration.name

        fun expandedName(replacement: (String) -> String?): String? {
            val original = name ?: return null
            if (replacement(original) == null) return null
            val errors = ErrorList()
            val tokens = Tokenizer.tokenizeWithEquivalences(
                sourceLine(declaration, fromLineStart = true), errors, replacement, ProgressManager::checkCanceled,
            )
            if (errors.errorsOccurred() || !tokens.beginsWithLabel()) return null
            return tokens[0].value.takeUnless { it == original }
        }
    }

    data class Begin(
        val header: PsiElement, val name: String, val nameOffset: Int, val directiveOffset: Int,
        val parameters: List<MacroFormal>,
        val arity: Int?, // A malformed signature still opens a scope for its accepted parameters.
    ) : Effective {
        override val offset = header.textOffset
        val parametersByName: Map<String, MacroFormal> by lazy {
            buildMap { for (parameter in parameters) putIfAbsent(parameter.value, parameter) }
        }
    }

    data class MacroFormal(val value: String, val offset: Int)

    data class End(override val offset: Int) : Effective

    data class Include(val literal: MipsStringLiteral, val path: String) : Effective {
        override val offset = literal.textOffset
    }

    data class DataDirective(override val offset: Int, val directive: Directives) : Effective

    data class Equivalence(val site: PsiElement, val name: String, val replacement: String) : Effective {
        override val offset = site.textOffset
    }

    class ExternalDeclaration(private val declaration: MipsDeclaration) : MipsSourceEvent {
        override val offset = declaration.textOffset

        fun bind(replacement: (String) -> String?): External? {
            val errors = ErrorList()
            val tokens = Tokenizer.tokenizeWithEquivalences(
                sourceLine(declaration.parent.firstChild), errors, replacement, ProgressManager::checkCanceled,
            )
            if (errors.errorsOccurred()) return null
            tokens.discardTrailingComment()
            return externalReservation(declaration, tokens, 0)
        }
    }

    data class External(val site: PsiElement, val name: String) : Effective {
        override val offset = site.textOffset
    }
}

private val directiveStatement = MipsTypes.psi("DIRECTIVE_STATEMENT")
private val operationCandidateType = MipsTypes.psi("OPERATION_CANDIDATE")
private val labelType = MipsTypes.psi("LABEL")
private val wordTypes = setOf(MipsTypes.psi("WORD"), MipsTypes.psi("DOLLAR_WORD"))
private val sourceEventTypes = setOf(
    MipsTypes.psi("MACRO_SIGNATURE"), MipsTypes.psi("END_MACRO"), MipsTypes.psi("STRING"), directiveStatement,
    MipsTypes.psi("EQUIVALENCE"), MipsTypes.psi("EXTERNAL_NAME"), labelType,
)
private val dataStateDirectives = setOf(
    Directives.DATA, Directives.KDATA, Directives.TEXT, Directives.KTEXT,
    Directives.WORD, Directives.HALF, Directives.BYTE, Directives.FLOAT, Directives.DOUBLE, Directives.ASCII, Directives.ASCIIZ,
)

/** Physical source events that affect macro boundaries or the current data list. */
internal fun mipsSourceEvents(file: MipsFile): List<MipsSourceEvent> = CachedValuesManager.getCachedValue(file) {
    val paths = mipsIncludePaths(file)
    val events = buildList {
        for (element in SyntaxTraverser.psiTraverser(file).filterTypes { it in sourceEventTypes || it in operationOwners }) {
            ProgressManager.checkCanceled()
            when (element) {
                is MipsDeclaration -> when (element.kind) {
                    SymbolKind.MACRO -> add(MipsSourceEvent.Header(element))
                    SymbolKind.LABEL -> add(MipsSourceEvent.LabelDeclaration(element))
                    SymbolKind.CONSTANT -> {
                        val name = element.name ?: continue
                        val replacement = Tokenizer.readEquivalenceReplacement(sourceLine(element, fromLineStart = true), name)
                            ?: continue
                        add(MipsSourceEvent.Equivalence(element, name, replacement))
                    }
                    SymbolKind.EXTERNAL -> add(MipsSourceEvent.ExternalDeclaration(element))
                }
                is MipsStringLiteral -> paths[element.textOffset]?.let { add(MipsSourceEvent.Include(element, it)) }
                else -> when (element.node.elementType) {
                    in operationOwners -> add(MipsSourceEvent.OperationCandidate(element))
                    directiveStatement -> {
                        val directive = Directives.matchDirective(element.firstChild.text)
                        if (directive in dataStateDirectives) add(MipsSourceEvent.DataDirective(element.textOffset, directive))
                        if (directive == Directives.EQV) add(MipsSourceEvent.OperationCandidate(element))
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
    val start = if (fromLineStart) sourceLineStart(element) else offset
    val end = CharArrayUtil.shiftForwardUntil(text, offset, "\r\n")
    return text.subSequence(start, end).toString()
}

internal fun sourceLineStart(element: PsiElement): Int =
    sourceLineStart(element.containingFile.viewProvider.contents, element.textOffset)

internal fun sourceLineStart(source: CharSequence, offset: Int): Int =
    CharArrayUtil.shiftBackwardUntil(source, offset - 1, "\r\n") + 1

/** A candidate can acquire an operation or label from either of its first two written tokens. */
internal fun hasLeadingEquivalence(head: PsiElement, replacement: (String) -> String?): Boolean {
    if (replacement(head.text) != null) return true
    return followingEquivalenceName(head)?.let(replacement) != null
}

internal fun followingEquivalenceName(head: PsiElement): String? {
    val file = head.containingFile
    val offset = CharArrayUtil.shiftForward(file.viewProvider.contents, head.textRange.endOffset, " \t,")
    val following = file.findElementAt(offset) ?: return null
    return following.text.takeIf { following.elementType in wordTypes }
}

internal fun TokenList.discardTrailingComment() {
    if (this[size() - 1].type == TokenTypes.COMMENT) remove(size() - 1)
}

private fun macroSignature(
    header: PsiElement, tokens: TokenList, nameIndex: Int, start: Int, mapped: Tokenizer.MappedLine? = null,
): MipsSourceEvent.Begin? {
    if (nameIndex >= tokens.size() || tokens[nameIndex].type != TokenTypes.IDENTIFIER) return null
    fun originalOffset(token: Token): Int {
        val column = token.startPos - 1
        return start + (mapped?.originalOffset(column) ?: column)
    }
    val arguments = (nameIndex + 1 until tokens.size()).map(tokens::get)
        .filter { it.type != TokenTypes.LEFT_PAREN && it.type != TokenTypes.RIGHT_PAREN }
    val parameters = arguments.takeWhile { Macro.tokenIsMacroParameter(it.value, true) }
    val arity = arguments.size.takeIf { it == parameters.size }
    val name = tokens[nameIndex]
    val directive = if (nameIndex == 0) start else originalOffset(tokens[nameIndex - 1])
    return MipsSourceEvent.Begin(header, name.value, originalOffset(name), directive,
        parameters.map { MipsSourceEvent.MacroFormal(it.value, originalOffset(it)) }, arity)
}

private fun isMacroEnd(element: PsiElement): Boolean = macroLineTokens(element, fromLineStart = true).size() == 1

private fun externalReservation(site: PsiElement, tokens: TokenList, operation: Int): MipsSourceEvent.External? {
    if (tokens.size() != operation + 3) return null
    val size = tokens[operation + 2]
    if (!TokenTypes.isIntegerTokenType(size.type) || Binary.stringToInt(size.value) < 0) return null
    val name = tokens[operation + 1]
    if (name.type != TokenTypes.IDENTIFIER && name.type != TokenTypes.OPERATOR) return null
    return MipsSourceEvent.External(site, name.value)
}
