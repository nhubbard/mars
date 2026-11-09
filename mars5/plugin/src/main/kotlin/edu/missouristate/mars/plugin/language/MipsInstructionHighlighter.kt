package edu.missouristate.mars.plugin.language

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType

internal val instructionMnemonicColor =
    TextAttributesKey.createTextAttributesKey("MARS_MIPS_INSTRUCTION", DefaultLanguageHighlighterColors.KEYWORD)

internal class MipsInstructionHighlighter : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.elementType != operationType) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).textAttributes(instructionMnemonicColor).create()
    }

    private companion object {
        val operationType = MipsTypes.psi("OPERATION")
    }
}
