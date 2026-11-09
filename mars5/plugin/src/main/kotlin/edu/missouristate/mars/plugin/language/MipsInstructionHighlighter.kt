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
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService

internal val instructionMnemonicColor =
    TextAttributesKey.createTextAttributesKey("MARS_MIPS_INSTRUCTION", DefaultLanguageHighlighterColors.KEYWORD)

internal class MipsInstructionHighlighter : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val mnemonic = when (element.elementType) {
            operationType -> element
            candidateType -> {
                val head = element.firstChild
                val file = element.containingFile as MipsFile
                val analysis = file.project.service<MarsAnalysisService>()
                analysis.expandedInstructionAlias(file, head, head.text) ?: return
                head
            }
            else -> return
        }
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(mnemonic).textAttributes(instructionMnemonicColor).create()
    }

    private companion object {
        val operationType = MipsTypes.psi("OPERATION")
        val candidateType = MipsTypes.psi("OPERATION_CANDIDATE")
    }
}
