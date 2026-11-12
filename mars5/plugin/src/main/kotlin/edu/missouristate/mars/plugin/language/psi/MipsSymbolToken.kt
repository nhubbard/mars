package edu.missouristate.mars.plugin.language.psi

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.MacroPart
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsInstructionOperand
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.equivalenceColor
import edu.missouristate.mars.plugin.language.labelColor
import edu.missouristate.mars.plugin.language.macroColor
import edu.missouristate.mars.plugin.language.macroParameterColor
import edu.missouristate.mars.plugin.language.operationHead
import edu.missouristate.mars.plugin.language.punctuationColor
import edu.missouristate.mars.plugin.language.registerColor
import edu.missouristate.mars.plugin.language.stringColor

private val instructionOperandContexts = TokenSet.create(MipsTypes.psi("REFERENCE_EXPR"), MipsTypes.psi("OPERAND_FRAGMENT"))
private val equivalenceContexts = TokenSet.orSet(
    instructionOperandContexts,
    TokenSet.create(
        MipsTypes.psi("SEGMENT_ADDRESS_SYMBOL"), MipsTypes.psi("OPERATION_CANDIDATE"), MipsTypes.psi("INVOCATION_TOKEN"),
        MipsTypes.psi("LABEL"), MipsTypes.psi("EXTERNAL_NAME"),
    ),
)
internal val equivalenceDefinitionParameterContexts = TokenSet.create(MipsTypes.psi("EQUIVALENCE"), MipsTypes.psi("REPLACEMENT"))
internal val macroParameterUseContexts = TokenSet.orSet(
    equivalenceContexts, TokenSet.create(MipsTypes.psi("DIRECTIVE_STATEMENT")),
    equivalenceDefinitionParameterContexts,
)

internal class MipsSymbolToken(type: IElementType, text: CharSequence) : LeafPsiElement(type, text) {
    override fun getReference(): PsiReference? {
        val identifier = TokenTypes.isValidIdentifier(text)
        val context = PsiUtilCore.getElementType(parent)
        val parameter = elementType in parameterTypes && context in macroParameterUseContexts
        val label = identifier && context in instructionOperandContexts &&
            MipsInstructionOperand.at(this, containingFile as MipsFile)?.acceptsLabel == true
        val aliasOperand = identifier && context == invocationToken
        val equivalence = identifier && elementType != mnemonic &&
            (context in equivalenceContexts || context in macroHeaderContexts)
        if (!label && !aliasOperand && !equivalence && !parameter) return null
        val data = if (equivalence) DataLabelOperand.at(this, context) else null
        return SymbolReference(this, label, aliasOperand, equivalence, parameter, context == operationCandidate, data)
    }

    override fun getReferences(): Array<PsiReference> = listOfNotNull(reference).toTypedArray()

    internal fun highlightingColor(): TextAttributesKey? {
        val file = containingFile as MipsFile
        val analysis = file.project.service<MarsAnalysisService>()
        val roleColor = when (analysis.generatedMacroPart(file, this)) {
            MacroPart.NAME -> macroColor
            MacroPart.PARAMETER -> macroParameterColor
            null -> return (reference as? SymbolReference)?.highlightingColor()
        }
        val use = analysis.equivalenceUse(file, this)
        return if (use != null && use.type == null) equivalenceColor else roleColor
    }

    private class SymbolReference(
        element: MipsSymbolToken,
        private val label: Boolean,
        private val aliasOperand: Boolean,
        private val equivalence: Boolean,
        private val parameter: Boolean,
        private val macroCall: Boolean,
        private val data: DataLabelOperand?,
    ) :
        PsiPolyVariantReferenceBase<MipsSymbolToken>(element, TextRange.from(0, element.textLength), true) {
        override fun handleElementRename(newElementName: String): PsiElement {
            if (!parameter) throw formalRenameError("symbol.rename.unavailable")
            return element.replace(macroFormalNameElement(element.project, newElementName))
        }

        override fun multiResolve(incompleteCode: Boolean): Array<PsiElementResolveResult> {
            val file = element.containingFile as MipsFile
            val analysis = file.project.service<MarsAnalysisService>()
            if (equivalence) {
                val target = analysis.equivalenceTarget(file, element)
                if (target != null) return arrayOf(PsiElementResolveResult(target))
            }
            if (parameter) {
                val targets = analysis.macroParameterTargets(file, element)
                if (targets != null) return targets.map(::PsiElementResolveResult).toTypedArray()
            }
            if (macroCall || aliasOperand || PsiUtilCore.getElementType(element.parent) in instructionOperandContexts) {
                val targets = analysis.macroTargets(file, element)
                if (targets.isNotEmpty()) return targets.map(::PsiElementResolveResult).toTypedArray()
            }
            val use = labelUse(file, analysis)
            return if (use != null) {
                analysis.labelTargets(file, element, allowExternal = use == LabelUse.REFERENCE)
                    .map(::PsiElementResolveResult).toTypedArray()
            } else emptyArray()
        }

        fun highlightingColor(): TextAttributesKey? {
            val file = element.containingFile as MipsFile
            val analysis = file.project.service<MarsAnalysisService>()
            val use = if (equivalence) analysis.equivalenceUse(file, element) else null
            if (use != null) {
                // The enclosing operation supplies the color when expansion moves its head onto this token.
                if (aliasOperand && use.suppliesOperation) return null
                return when (use.type) {
                    in numericTypes -> DefaultLanguageHighlighterColors.NUMBER
                    in registerTypes -> registerColor
                    in punctuationTypes -> punctuationColor
                    TokenTypes.QUOTED_STRING -> stringColor
                    TokenTypes.MACRO_PARAMETER -> macroParameterColor
                    TokenTypes.IDENTIFIER, TokenTypes.OPERATOR -> if (analysis.isGeneratedLabel(file, element.parent)) {
                        labelColor
                    } else operandColor(file, analysis) ?: equivalenceColor
                    else -> equivalenceColor
                }
            }
            if (parameter && analysis.macroParameterTargets(file, element) != null) return macroParameterColor
            return operandColor(file, analysis)
        }

        private fun operandColor(file: MipsFile, analysis: MarsAnalysisService): TextAttributesKey? {
            if (labelUse(file, analysis) != null) return labelColor
            if (!aliasOperand) return null
            val operation = analysis.operationSite(file, element.parent.parent.operationHead)
            return macroParameterColor.takeIf {
                operation?.type == TokenTypes.IDENTIFIER && operation.range.endOffset <= element.textOffset
            }
        }

        private fun labelUse(file: MipsFile, analysis: MarsAnalysisService): LabelUse? {
            val instructionLabel = label || aliasOperand && MipsInstructionOperand.at(element, file)?.acceptsLabel == true
            val exportsLabel = data == DataLabelOperand.EXPORT || aliasOperand && equivalence && !instructionLabel &&
                analysis.exportOperandsBefore(file, element.parent.parent.firstChild, element) != null
            return when {
                exportsLabel -> LabelUse.EXPORT
                instructionLabel || data?.isDataLabel(file, analysis, element) == true -> LabelUse.REFERENCE
                else -> null
            }
        }
    }

    private enum class LabelUse { EXPORT, REFERENCE }

    private companion object {
        val mnemonic = MipsTypes.psi("MNEMONIC")
        val operationCandidate = MipsTypes.psi("OPERATION_CANDIDATE")
        val invocationToken = MipsTypes.psi("INVOCATION_TOKEN")
        val macroHeaderContexts = TokenSet.create(MipsTypes.psi("MACRO_SIGNATURE"), MipsTypes.psi("MACRO_PARAMETER"))
        val parameterTypes = TokenSet.create(MipsTypes.psi("PARAMETER"), MipsTypes.psi("DOLLAR_WORD"))
        val numericTypes = setOf(TokenTypes.INTEGER_5, TokenTypes.INTEGER_16, TokenTypes.INTEGER_16U,
            TokenTypes.INTEGER_32, TokenTypes.REAL_NUMBER)
        val registerTypes = setOf(TokenTypes.REGISTER_NAME, TokenTypes.REGISTER_NUMBER, TokenTypes.FP_REGISTER_NAME)
        val punctuationTypes = setOf(TokenTypes.LEFT_PAREN, TokenTypes.RIGHT_PAREN, TokenTypes.COLON,
            TokenTypes.PLUS, TokenTypes.MINUS)
    }
}

internal enum class DataLabelOperand {
    EXPORT, LIST, CANDIDATE;

    fun exportHead(element: PsiElement): PsiElement? = when (this) {
        EXPORT -> element.parent.parent.parent.parent.firstChild
        LIST -> null
        CANDIDATE -> element.parent.takeIf { PsiUtilCore.getElementType(it) == invocationToken }?.let { it.parent.firstChild }
    }

    fun isDataLabel(file: MipsFile, analysis: MarsAnalysisService, element: PsiElement): Boolean {
        if (this == EXPORT || !analysis.hasIntegerDataList(file, element)) return false
        if (this == LIST) return true
        val candidateHead = PsiUtilCore.getElementType(element.parent) == candidateType
        val head = if (candidateHead) element else element.parent.parent.firstChild
        return analysis.isIntegerDataCandidate(file, head, candidateHead)
    }

    companion object {
        private val candidateType = MipsTypes.psi("OPERATION_CANDIDATE")
        private val invocationToken = MipsTypes.psi("INVOCATION_TOKEN")
        private val candidateTypes = TokenSet.create(candidateType, invocationToken)
        private val referenceType = MipsTypes.psi("REFERENCE_EXPR")
        private val operandType = MipsTypes.psi("OPERAND")
        private val repetitionType = MipsTypes.psi("REPETITION")
        private val continuationType = MipsTypes.psi("DATA_CONTINUATION")
        private val directiveType = MipsTypes.psi("DIRECTIVE_STATEMENT")
        private val signs = TokenSet.create(MipsTypes.psi("PLUS"), MipsTypes.psi("MINUS"))

        fun at(element: PsiElement, context: IElementType? = PsiUtilCore.getElementType(element.parent)): DataLabelOperand? {
            if (context in candidateTypes) return CANDIDATE
            if (context != referenceType) return null
            val operand = element.parent.parent
            if (PsiUtilCore.getElementType(operand) != operandType || operand.node.findChildByType(repetitionType) != null) return null
            val owner = operand.parent.parent
            val kind = when (PsiUtilCore.getElementType(owner)) {
                continuationType -> LIST
                directiveType -> {
                    val directive = Directives.matchDirective(owner.firstChild.text)
                    when {
                        directive == Directives.GLOBL -> EXPORT
                        Directives.isIntegerDirective(directive) -> LIST
                        else -> null
                    }
                }
                else -> null
            }
            // A real label prevents the assembler from joining the following sign to a number.
            // An equivalence can still resolve here and supply a numeric token instead.
            return kind?.takeUnless { it == LIST && followedBySign(operand) }
        }

        private fun followedBySign(operand: PsiElement): Boolean {
            val next = generateSequence(operand.nextSibling) { it.nextSibling }
                .firstOrNull { PsiUtilCore.getElementType(it) == operandType } ?: return false
            return PsiUtilCore.getElementType(PsiTreeUtil.getDeepestFirst(next)) in signs
        }
    }
}
