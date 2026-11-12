package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.Presentation
import com.intellij.modcommand.PsiBasedModCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import edu.missouristate.mars.plugin.analysis.MacroBoundaryProblem
import edu.missouristate.mars.plugin.analysis.MacroBoundaryProblem.Kind
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.mipsAssemblyRoots

/** Reports macro expansion errors from the same timeline used for scope and navigation. */
internal class MipsMacroDiagnosticAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? MipsFile ?: return
        val analysis = file.project.service<MarsAnalysisService>()
        val timeline = analysis.editorTimeline(file)
        val hasEntry by lazy(LazyThreadSafetyMode.NONE) {
            hasAssemblyEntry(file, analysis)
        }
        for (macro in timeline.localMacros(file).values) {
            ProgressManager.checkCanceled()
            val invalid = macro.invalidFormal ?: continue
            // A physical syntax error on this header already explains the rejected parameter list.
            if (PsiTreeUtil.hasErrorElements(macro.header)) continue
            val site = file.findElementAt(invalid.offset)!!
            if (PsiTreeUtil.getParentOfType(site, PsiErrorElement::class.java) != null) continue
            if (!hasEntry) return
            holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.macro.formal.expanded", invalid.value))
                .range(site.textRange).create()
        }
        for ((offset, kind) in timeline.macroBoundaryProblems(file)) {
            ProgressManager.checkCanceled()
            if (!hasEntry) return
            val site = file.findElementAt(offset)!!
            val message = when (kind) {
                Kind.MISSING_NAME -> "syntax.macro.name.missing"
                Kind.INVALID_NAME -> "syntax.macro.name.invalid"
                Kind.NESTED_BEGIN -> "syntax.macro.nested"
                Kind.TRAILING_TEXT -> "syntax.macro.end.trailing"
                Kind.UNMATCHED_END -> "syntax.macro.end.unmatched"
                Kind.MISSING_END -> "syntax.macro.end.missing"
            }
            val annotation = holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage(message)).range(site.textRange)
            if (kind == Kind.MISSING_END) annotation.withFix(CloseUnterminatedMacro(site))
            annotation.create()
        }
    }
}

private class CloseUnterminatedMacro(site: PsiElement) : PsiBasedModCommandAction<PsiElement>(site) {
    override fun getFamilyName(): String = bundle.getMessage("syntax.macro.end.add")

    override fun getPresentation(context: ActionContext, element: PsiElement): Presentation? {
        val entry = missingEndEntry(element) ?: return null
        val title = if (entry == element.containingFile) familyName else bundle.getMessage("syntax.macro.end.addTo", entry.name)
        return Presentation.of(title)
    }

    override fun perform(context: ActionContext, element: PsiElement): ModCommand {
        val entry = missingEndEntry(element) ?: return ModCommand.nop()
        // Appending to the entry keeps the whole include-expanded body inside the macro.
        return ModCommand.psiUpdate(entry) { writable ->
            val document = writable.viewProvider.document!!
            val separator = if (document.charsSequence.endsWith('\n')) "" else "\n"
            document.insertString(document.textLength, "$separator.end_macro\n")
        }
    }

    private fun missingEndEntry(element: PsiElement): MipsFile? {
        val file = element.containingFile as MipsFile
        val analysis = file.project.service<MarsAnalysisService>()
        if (!hasAssemblyEntry(file, analysis)) return null
        val timeline = analysis.editorTimeline(file)
        return timeline.entryFile.takeIf {
            MacroBoundaryProblem(element.textOffset, Kind.MISSING_END) in timeline.macroBoundaryProblems(file)
        }
    }
}

private fun hasAssemblyEntry(file: MipsFile, analysis: MarsAnalysisService): Boolean =
    analysis.selectedAssembly(file) != null || mipsAssemblyRoots(file).size == 1

private val bundle = DynamicBundle(MipsMacroDiagnosticAnnotator::class.java, "messages.MarsBundle")
