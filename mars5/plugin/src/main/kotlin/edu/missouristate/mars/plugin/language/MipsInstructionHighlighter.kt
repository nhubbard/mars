package edu.missouristate.mars.plugin.language

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService

internal val instructionMnemonicColor =
    TextAttributesKey.createTextAttributesKey("MARS_MIPS_INSTRUCTION", DefaultLanguageHighlighterColors.KEYWORD)

internal class MipsInstructionHighlighter : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.elementType !in operationOwners) return
        val file = element.containingFile as MipsFile
        val site = file.project.service<MarsAnalysisService>().operationSite(file, element.operationHead) ?: return
        if (site.type != TokenTypes.OPERATOR) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(site.range).textAttributes(instructionMnemonicColor).create()
    }
}
