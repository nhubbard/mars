package edu.missouristate.mars.plugin.language

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.analysis.BackgroundAssemblyMemo
import edu.missouristate.mars.plugin.analysis.BackgroundAssemblyOutcome
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService.AssemblySelection
import edu.missouristate.mars.plugin.analysis.collectBackgroundAssemblySources
import edu.missouristate.mars.plugin.analysis.inferredMipsAssemblyEntry
import edu.missouristate.mars.plugin.analysis.validateSourceDocuments
import edu.missouristate.mars.plugin.language.syntax.MipsScanner
import java.nio.file.Path

/** IntelliJ owns scheduling and cancellation. The assembler receives only captured source text. */
internal class MipsAssemblyAnnotator : ExternalAnnotator<MipsAssemblyAnnotator.Input, MipsAssemblyAnnotator.Result>() {
    data class Input(
        val source: Path,
        val selectedAssembly: AssemblySelection?,
        val sources: CapturedAssemblySources,
        val options: MarsExecutionOptions,
        val structureStamp: Long,
        val assembly: BackgroundAssemblyMemo,
    )

    data class Result(val input: Input, val outcome: BackgroundAssemblyOutcome) {
        val diagnostics = outcome.diagnostics[input.source].orEmpty()
        val symbols = outcome.symbols
    }

    override fun collectInformation(psiFile: PsiFile): Input? {
        val file = psiFile as? MipsFile ?: return null
        val virtualFile = file.viewProvider.virtualFile
        if (!virtualFile.isInLocalFileSystem) return null
        val source = virtualFile.toNioPath().toAbsolutePath().normalize()
        val analysis = file.project.service<MarsAnalysisService>()
        val selected = analysis.selectedAssembly(file)
        val explicit = selected?.let { selection ->
            collectBackgroundAssemblySources(file.project, selection.entry).takeIf { source in it.revisions }
                ?.let { it to selection.options }
        }
        val (sources, options) = explicit ?: (
            collectBackgroundAssemblySources(file.project, inferredMipsAssemblyEntry(file) ?: return null) to MarsExecutionOptions()
        )
        return Input(source, selected, sources, options, VirtualFileManager.getInstance().structureModificationCount, analysis.backgroundAssembly)
    }

    override fun doAnnotate(collectedInfo: Input): Result =
        Result(collectedInfo, collectedInfo.assembly.assemble(collectedInfo.sources, collectedInfo.structureStamp, collectedInfo.options))

    override fun apply(psiFile: PsiFile, annotationResult: Result, holder: AnnotationHolder) {
        for ((diagnostic, range) in acceptResult(psiFile as MipsFile, annotationResult)) {
            val severity = if (diagnostic.isWarning) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            holder.newAnnotation(severity, diagnostic.message()).range(range).create()
        }
    }

    internal fun acceptResult(file: MipsFile, result: Result): List<Pair<AssemblyError, TextRange>> {
        val diagnostics = validatedDiagnostics(file, result) ?: return emptyList()
        result.symbols?.let { file.project.service<MarsAnalysisService>().publishBackgroundSymbols(it) }
        return diagnostics
    }

    private fun validatedDiagnostics(file: MipsFile, result: Result): List<Pair<AssemblyError, TextRange>>? {
        if (result.diagnostics.isEmpty() && result.symbols == null) return null
        val input = result.input
        if (VirtualFileManager.getInstance().structureModificationCount != input.structureStamp) return null
        val selected = file.project.service<MarsAnalysisService>().selectedAssembly(file)
        if (selected != input.selectedAssembly) return null
        val entry = input.sources.snapshot.entries().single()
        if (entry != selected?.entry && entry != inferredMipsAssemblyEntry(file)) return null
        var ranges = emptyList<Pair<AssemblyError, TextRange>>()
        val valid = input.sources.revisions.validateSourceDocuments { path, document, revision ->
            if (path == input.source) ranges = result.diagnostics.mapNotNull { error ->
                diagnosticRange(document, error)?.let { error to it }
            }
            document.modificationStamp == revision
        }
        return ranges.takeIf { valid }
    }
}

internal fun diagnosticRange(document: Document, error: AssemblyError): TextRange? {
    val line = error.line() - 1
    if (line !in 0 until document.lineCount) return null
    val start = document.getLineStartOffset(line)
    val end = document.getLineEndOffset(line)
    val offset = start + (error.column() - 1).coerceIn(0, end - start)
    val token = MipsScanner.next(document.charsSequence, offset, end) ?: return TextRange(start, end)
    return TextRange(offset, token.end)
}
