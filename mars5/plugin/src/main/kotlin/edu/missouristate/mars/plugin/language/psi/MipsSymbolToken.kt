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
                    in registerTypes -> DefaultLanguageHighlighterColors.INSTANCE_FIELD
                    in punctuationTypes -> punctuationColor
                    TokenTypes.QUOTED_STRING -> DefaultLanguageHighlighterColors.STRING
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
                analysis.isAliasedExportOperand(file, element.parent.parent.firstChild, element)
            return when {
                exportsLabel -> LabelUse.EXPORT
                instructionLabel || data != null && resolvesDataLabel(file, analysis) -> LabelUse.REFERENCE
                else -> null
            }
        }

        private fun resolvesDataLabel(file: MipsFile, analysis: MarsAnalysisService): Boolean {
            if (!analysis.hasIntegerDataList(file, element)) return false
            if (data == DataLabelOperand.LIST) return true
            val head = if (macroCall) element else element.parent.parent.firstChild
            return analysis.isIntegerDataCandidate(file, head, macroCall)
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

private enum class DataLabelOperand {
    EXPORT, LIST, CANDIDATE;

    companion object {
        private val candidateTypes = TokenSet.create(MipsTypes.psi("OPERATION_CANDIDATE"), MipsTypes.psi("INVOCATION_TOKEN"))
        private val referenceType = MipsTypes.psi("REFERENCE_EXPR")
        private val operandType = MipsTypes.psi("OPERAND")
        private val repetitionType = MipsTypes.psi("REPETITION")
        private val continuationType = MipsTypes.psi("DATA_CONTINUATION")
        private val directiveType = MipsTypes.psi("DIRECTIVE_STATEMENT")

        fun at(element: LeafPsiElement, context: IElementType?): DataLabelOperand? {
            if (context in candidateTypes) return CANDIDATE
            if (context != referenceType) return null
            val operand = element.parent.parent
            if (PsiUtilCore.getElementType(operand) != operandType || operand.node.findChildByType(repetitionType) != null) return null
            val owner = operand.parent.parent
            return when (PsiUtilCore.getElementType(owner)) {
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
        }
    }
}
