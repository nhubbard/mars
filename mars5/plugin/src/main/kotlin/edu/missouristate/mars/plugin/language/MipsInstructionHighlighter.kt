package edu.missouristate.mars.plugin.language

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.psi.util.elementType
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsSymbolToken

internal val instructionMnemonicColor =
    TextAttributesKey.createTextAttributesKey("MARS_MIPS_INSTRUCTION", DefaultLanguageHighlighterColors.KEYWORD)

// Follow the theme's Kotlin colors with the same platform fallback if Kotlin is unavailable.
internal val labelColor = TextAttributesKey.createTextAttributesKey(
    "MARS_MIPS_LABEL", TextAttributesKey.createTextAttributesKey("KOTLIN_LABEL", DefaultLanguageHighlighterColors.LABEL),
)
internal val macroColor = TextAttributesKey.createTextAttributesKey(
    "MARS_MIPS_MACRO", TextAttributesKey.createTextAttributesKey(
        "KOTLIN_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
    ),
)
internal val equivalenceColor =
    TextAttributesKey.createTextAttributesKey("MARS_MIPS_EQUIVALENCE", DefaultLanguageHighlighterColors.CONSTANT)
internal val equivalenceReplacementColor =
    TextAttributesKey.createTextAttributesKey("MARS_MIPS_EQUIVALENCE_REPLACEMENT", equivalenceColor)

internal class MipsInstructionHighlighter : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when {
            element is MipsDeclaration -> {
                val identifier = element.nameIdentifier ?: return
                holder.color(identifier.textRange, element.kind.color)
            }
            element.elementType in operationOwners -> highlightOperation(element, holder)
            element.elementType == macroParameter -> holder.color(element.textRange, macroParameterColor)
            element.elementType in signs -> highlightSign(element, holder)
            element.elementType == replacement -> {
                val token = element.firstChild
                // A replacement identifier can expand into different roles at different use sites.
                if (token.elementType in replacementIdentifiers) holder.color(token.textRange, equivalenceReplacementColor)
            }
            element is MipsSymbolToken && element.parent.elementType !in operationOwners -> {
                val color = element.highlightingColor() ?: return
                holder.color(element.textRange, color)
            }
        }
    }

    private fun highlightSign(sign: PsiElement, holder: AnnotationHolder) {
        if (sign.parent.elementType == addition) return
        if (PsiUtilCore.getElementType(PsiTreeUtil.nextLeaf(sign, true)) == number) {
            holder.color(sign.textRange, DefaultLanguageHighlighterColors.NUMBER)
        }
    }

    private fun highlightOperation(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile as MipsFile
        val analysis = file.project.service<MarsAnalysisService>()
        val head = element.operationHead
        val site = analysis.operationSite(file, head, recognizeMacros = true)
        when (site?.type) {
            TokenTypes.OPERATOR -> holder.color(site.range, instructionMnemonicColor)
            TokenTypes.IDENTIFIER -> holder.color(site.range, macroColor)
            TokenTypes.DIRECTIVE -> holder.color(site.range, DefaultLanguageHighlighterColors.KEYWORD)
            else -> Unit
        }
        if (site?.range != head.textRange && analysis.isGeneratedLabel(file, head)) holder.color(head.textRange, labelColor)
    }

    private companion object {
        val macroParameter = MipsTypes.psi("MACRO_PARAMETER")
        val signs = TokenSet.create(MipsTypes.psi("PLUS"), MipsTypes.psi("MINUS"))
        val addition = MipsTypes.psi("ADD_EXPR")
        val number = MipsTypes.psi("NUMBER")
        val replacement = MipsTypes.psi("REPLACEMENT")
        val replacementIdentifiers = TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("MNEMONIC"))

        val SymbolKind.color: TextAttributesKey get() = when (this) {
            SymbolKind.LABEL, SymbolKind.EXTERNAL -> labelColor
            SymbolKind.MACRO -> macroColor
            SymbolKind.CONSTANT -> equivalenceColor
        }

        fun AnnotationHolder.color(range: TextRange, key: TextAttributesKey) {
            newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
        }
    }
}
