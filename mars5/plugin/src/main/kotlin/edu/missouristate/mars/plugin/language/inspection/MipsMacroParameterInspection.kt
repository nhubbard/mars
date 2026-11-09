package edu.missouristate.mars.plugin.language.inspection

import com.intellij.DynamicBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.elementType
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsSourceEvent.Begin
import edu.missouristate.mars.plugin.analysis.MipsSourceTimeline
import edu.missouristate.mars.plugin.analysis.sourceLineStart
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration

/** Checks definitions even when no invocation has reached the assembler. */
internal class MipsMacroParameterInspection : LocalInspectionTool() {
    // Header or equivalence edits can change a parameter's meaning on a later physical line.
    override fun runForWholeFile() = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as MipsFile
        val timeline = file.project.service<MarsAnalysisService>().editorTimeline(file)
        if (timeline.macroNames.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.elementType != statement) return
                val source = file.viewProvider.contents
                val start = sourceLineStart(element)
                val end = CharArrayUtil.shiftForwardUntil(source, element.textOffset, "\r\n")
                val before = timeline.macroAt(file, start)
                val after = timeline.macroAt(file, end) ?: return
                when {
                    after === before ->
                        inspectBody(holder, file, timeline.prefix(file, start), after, start, end)
                    after.header.containingFile === file ->
                        inspectHeader(holder, file, timeline.prefix(file, start), after, start, end)
                }
            }
        }
    }

    private fun inspectHeader(
        holder: ProblemsHolder, file: MipsFile, prefix: MipsSourceTimeline.Prefix, macro: Begin, start: Int, end: Int,
    ) {
        val seen = HashSet<String>()
        val duplicates = macro.parameters.filter { !seen.add(it.value) }
        if (duplicates.isEmpty()) return

        // A literal header is tokenized from its name, or from .macro if an equivalence was expanded.
        // An aliased header is tokenized from the beginning of the physical line.
        val origin = when {
            !macro.expanded -> macro.offset
            macro.header is MipsDeclaration -> macro.header.parent.firstChild.textOffset
            else -> start
        }
        val mapped = if (macro.expanded) expand(file, prefix, origin, end, ErrorList()) else null
        val reported = HashSet<Pair<Int, String>>()
        for (token in duplicates) {
            val column = token.startPos - 1
            val offset = origin + (mapped?.originalOffset(column) ?: column)
            if (reported.add(offset to token.value)) {
                holder.registerProblem(file.findElementAt(offset)!!, messages.getMessage("inspection.macro.duplicate", token.value))
            }
        }
    }

    private fun inspectBody(
        holder: ProblemsHolder, file: MipsFile, prefix: MipsSourceTimeline.Prefix, macro: Begin, start: Int, end: Int,
    ) {
        val errors = ErrorList()
        val mapped = expand(file, prefix, start, end, errors)
        if (errors.errorsOccurred()) return
        val tokens = mapped.tokens()

        val reported = HashSet<Pair<Int, String>>()
        for (index in 0 until tokens.size()) {
            val token = tokens[index]
            val name = token.value
            if (index == 0 && Directives.matchDirective(name) in boundaries) return
            if (!Macro.tokenIsMacroParameter(name, true) || name in macro.parametersByName) continue
            val offset = start + mapped.originalOffset(token.startPos - 1)
            if (reported.add(offset to name)) {
                holder.registerProblem(file.findElementAt(offset)!!, messages.getMessage("inspection.macro.unknown", name, macro.name))
            }
        }
    }

    private fun expand(
        file: MipsFile, prefix: MipsSourceTimeline.Prefix, start: Int, end: Int, errors: ErrorList,
    ): Tokenizer.MappedLine {
        val line = file.viewProvider.contents.subSequence(start, end).toString()
        return Tokenizer.tokenizeMappedLine(line, errors, { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled)
    }

    private companion object {
        val messages = DynamicBundle(MipsMacroParameterInspection::class.java, "messages.MarsBundle")
        val statement = MipsTypes.psi("STATEMENT")
        val boundaries = setOf(Directives.MACRO, Directives.END_MACRO, Directives.INCLUDE)
    }
}
