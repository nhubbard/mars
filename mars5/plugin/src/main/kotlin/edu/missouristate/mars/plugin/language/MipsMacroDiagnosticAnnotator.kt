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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.analysis.MacroBoundaryProblem
import edu.missouristate.mars.plugin.analysis.MacroBoundaryProblem.Kind
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsSourceTimeline
import edu.missouristate.mars.plugin.analysis.discardTrailingComment
import edu.missouristate.mars.plugin.analysis.mipsAssemblyRoots
import edu.missouristate.mars.plugin.analysis.mipsSourceTimeline
import edu.missouristate.mars.plugin.analysis.sourceLine
import edu.missouristate.mars.plugin.analysis.sourceLineStart

/** Reports macro expansion errors from the same timeline used for scope and navigation. */
internal class MipsMacroDiagnosticAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element is PsiErrorElement && element.errorDescription == bundle.getMessage("syntax.macro.end.trailing")) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element.textRange)
                .withFix(CommentAfterMacroEnd(element)).create()
            return
        }
        val file = element as? MipsFile ?: return
        val analysis = file.project.service<MarsAnalysisService>()
        val timeline = analysis.editorTimeline(file)
        val hasEntry by lazy(LazyThreadSafetyMode.NONE) {
            hasAssemblyEntry(file, analysis)
        }
        for (macro in timeline.localMacros(file).values) {
            ProgressManager.checkCanceled()
            val invalid = macro.invalidFormal ?: continue
            val site = file.findElementAt(invalid.offset)!!
            val range = macroDiagnosticRange(site, invalid.lastOffset)
            val physicalError = PsiTreeUtil.getParentOfType(site, PsiErrorElement::class.java)
            if (physicalError != null && !physicalError.isSupersededFormalError(range)) continue
            if (!hasEntry) return
            if (timeline.prefix(file, sourceLineStart(site)).hasIncompleteIncludes) continue
            holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.macro.formal.expanded", invalid.value))
                .range(range).create()
        }
        for ((offset, kind, lastOffset) in timeline.macroBoundaryProblems(file)) {
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
            val annotation = holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage(message))
                .range(macroDiagnosticRange(site, lastOffset))
            if (kind == Kind.MISSING_END) annotation.withFix(CloseUnterminatedMacro(site))
            if (kind == Kind.TRAILING_TEXT) annotation.withFix(CommentAfterMacroEnd(site))
            if (kind == Kind.UNMATCHED_END) annotation.withFix(CommentOutUnmatchedMacroEnd(site))
            annotation.create()
        }
    }
}

private fun macroDiagnosticRange(site: PsiElement, lastOffset: Int): TextRange =
    site.textRange.union(site.containingFile.findElementAt(lastOffset)!!.textRange)

internal fun supersededMacroFormalError(file: MipsFile, error: PsiErrorElement, analysis: MarsAnalysisService): Boolean {
    if (error.errorDescription != bundle.getMessage("syntax.macro.formal.invalid")) return false
    if (DumbService.isDumb(file.project)) return false
    val timeline = analysis.editorTimeline(file)
    val lineStart = sourceLineStart(error)
    // The assembler rejects these headers before it checks any of their parameters.
    if (timeline.rejectsMacroHeaderOnLine(file, lineStart)) return hasAssemblyEntry(file, analysis)
    val invalid = timeline.localMacros(file)[error.parent]?.invalidFormal ?: return false
    val site = file.findElementAt(invalid.offset)!!
    val range = macroDiagnosticRange(site, invalid.lastOffset)
    return error.isSupersededFormalError(range) &&
        !timeline.prefix(file, lineStart).hasIncompleteIncludes && hasAssemblyEntry(file, analysis)
}

private fun PsiErrorElement.isSupersededFormalError(effective: TextRange): Boolean =
    errorDescription == bundle.getMessage("syntax.macro.formal.invalid") &&
        (effective.startOffset < textRange.startOffset || effective.endOffset > textRange.endOffset)

private class CommentOutUnmatchedMacroEnd(site: PsiElement) : PsiBasedModCommandAction<PsiElement>(site) {
    override fun getFamilyName(): String = bundle.getMessage("syntax.macro.end.commentUnmatched")

    override fun getPresentation(context: ActionContext, element: PsiElement): Presentation? =
        commentOffset(element)?.let { Presentation.of(familyName) }

    override fun perform(context: ActionContext, element: PsiElement): ModCommand {
        val offset = commentOffset(element) ?: return ModCommand.nop()
        return ModCommand.psiUpdate(element.containingFile) { writable ->
            writable.viewProvider.document!!.insertString(offset, "# ")
        }
    }

    private fun commentOffset(element: PsiElement): Int? {
        val file = element.containingFile as MipsFile
        val analysis = file.project.service<MarsAnalysisService>()
        if (!hasAssemblyEntry(file, analysis)) return null
        val timeline = analysis.editorTimeline(file)
        val offset = element.textOffset
        if (!canComment(file, element, timeline)) return null
        // An included closer can be stray for one entry and close a real macro in another.
        for (root in mipsAssemblyRoots(file)) {
            if (root == timeline.entryFile.viewProvider.virtualFile) continue
            val entry = PsiManager.getInstance(file.project).findFile(root) as MipsFile
            if (!canComment(file, element, mipsSourceTimeline(entry))) return null
        }
        return offset
    }

    private fun canComment(file: MipsFile, element: PsiElement, timeline: MipsSourceTimeline): Boolean {
        val offset = element.textOffset
        if (MacroBoundaryProblem(offset, Kind.UNMATCHED_END) !in timeline.macroBoundaryProblems(file)) return false
        val start = sourceLineStart(element)
        val prefix = timeline.prefix(file, start)
        val line = sourceLine(element, fromLineStart = true)
        val mapped = Tokenizer.tokenizeMappedLine(line, ErrorList(), { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled)
        val tokens = mapped.tokens()
        // Keep the entire label, including its colon, if an alias supplies part of it.
        return !tokens.beginsWithLabel() || start + mapped.originalOffset(tokens[1].startPos - 1) < offset
    }
}

private class CommentAfterMacroEnd(site: PsiElement) : PsiBasedModCommandAction<PsiElement>(site) {
    override fun getFamilyName(): String = bundle.getMessage("syntax.macro.end.commentTail")

    override fun getPresentation(context: ActionContext, element: PsiElement): Presentation? =
        commentOffset(element)?.let { Presentation.of(familyName) }

    override fun perform(context: ActionContext, element: PsiElement): ModCommand {
        val offset = commentOffset(element) ?: return ModCommand.nop()
        return ModCommand.psiUpdate(element.containingFile) { writable ->
            writable.viewProvider.document!!.insertString(offset, "# ")
        }
    }

    private fun commentOffset(element: PsiElement): Int? {
        val file = element.containingFile as MipsFile
        val analysis = file.project.service<MarsAnalysisService>()
        if (!hasAssemblyEntry(file, analysis)) return null
        val start = sourceLineStart(element)
        val prefix = analysis.editorTimeline(file).prefix(file, start)
        if (prefix.hasIncompleteIncludes || prefix.openMacro == null) return null
        val line = sourceLine(element, fromLineStart = true)
        if (Tokenizer().inspectInclude(line) != null) return null
        val mapped = Tokenizer.tokenizeMappedLine(line, ErrorList(), { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled)
        val tokens = mapped.tokens()
        tokens.discardTrailingComment()
        if (tokens.size() < 2 || Directives.matchDirective(tokens[0].value) != Directives.END_MACRO) return null
        val head = mapped.originalOffset(tokens[0].startPos - 1)
        val tail = mapped.originalOffset(tokens[1].startPos - 1)
        // When one alias supplies both tokens, commenting it out would also remove the closer.
        return (start + tail).takeIf { tail > head }
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
