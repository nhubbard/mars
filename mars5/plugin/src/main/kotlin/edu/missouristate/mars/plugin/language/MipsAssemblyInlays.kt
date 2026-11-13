package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.ui.update.DebouncedUpdates
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.analysis.AssemblySymbols
import edu.missouristate.mars.plugin.analysis.BackgroundAssemblyOutcome
import edu.missouristate.mars.plugin.analysis.EditorAssemblyCapture
import edu.missouristate.mars.plugin.analysis.MipsAssemblyContextListener
import edu.missouristate.mars.plugin.analysis.collectEditorAssembly
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.util.Binary
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.annotations.TestOnly
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal const val MIPS_ADDRESS_HINTS_ID = "mars.mips.assembled.addresses"
internal const val MIPS_EXPANSION_HINTS_ID = "mars.mips.assembled.expansions"

internal class MipsAddressInlayProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): OwnBypassCollector? =
        AddressCollector.takeIf { file is MipsFile }
}

internal class MipsExpansionInlayProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): OwnBypassCollector? =
        ExpansionCollector.takeIf { file is MipsFile }
}

private object AddressCollector : OwnBypassCollector {
    override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
        val mips = file as MipsFile
        val snapshot = mips.currentAssemblyHints() ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(mips)!!
        for ((line, column, name, address) in snapshot.addresses) {
            ProgressManager.checkCanceled()
            val offset = document.getLineStartOffset(line - 1) + column - 1
            val declaration = MipsDeclaration.findAt(mips, offset, name) ?: continue
            val position = InlineInlayPosition(declaration.textRange.endOffset, relatedToPrevious = true)
            sink.addPresentation(position, tooltip = bundle.getMessage("inlay.address.tooltip"), hintFormat = HintFormat.default) {
                text(Binary.intToHexString(address))
            }
        }
    }
}

private object ExpansionCollector : OwnBypassCollector {
    override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
        val snapshot = (file as MipsFile).currentAssemblyHints() ?: return
        for ((line, instructions) in snapshot.expansions) {
            ProgressManager.checkCanceled()
            val count = bundle.getMessage("inlay.expansion.count", instructions.size)
            sink.addPresentation(EndOfLinePosition(line - 1), tooltip = bundle.getMessage("inlay.expansion.tooltip"),
                hintFormat = HintFormat.default) {
                collapsibleList(CollapseState.Collapsed,
                    expandedState = {
                        toggleButton { text(count) }
                        text(instructions.joinToString(" · ", prefix = "  "))
                    },
                    collapsedState = { toggleButton { text(count) } },
                )
            }
        }
    }
}

private val bundle = DynamicBundle(MipsAddressInlayProvider::class.java, "messages.MarsBundle")
private val assemblyHints = Key.create<MipsAssemblyHints>("mars.mips.assembled.inlays")

internal data class MipsAssemblyHintVersion(
    val sources: MarsSourceSet, val handler: Path?, val options: MarsExecutionOptions,
    val revisions: Map<Path, Long>, val structure: Long, val diskSources: Set<Path>,
)

internal data class MipsWrittenAddress(val line: Int, val column: Int, val name: String, val address: Int)

internal data class MipsAssemblyHints(
    val version: MipsAssemblyHintVersion,
    val addresses: List<MipsWrittenAddress>,
    val expansions: Map<Int, List<String>>,
)

private fun EditorAssemblyCapture.hintVersion() =
    MipsAssemblyHintVersion(sources.sourceSet, sources.exceptionHandler, options, sources.revisions, structureStamp, sources.diskSources)

private fun MipsFile.currentAssemblyHints(): MipsAssemblyHints? {
    val snapshot = getUserData(assemblyHints) ?: return null
    val documents = PsiDocumentManager.getInstance(project)
    if (!documents.isCommitted(documents.getDocument(this)!!)) return null
    val capture = collectEditorAssembly(this, readOnlyInclude = true) ?: return null
    return snapshot.takeIf { it.version == capture.hintVersion() }
}

/** Called in the detached assembly pass, before applying the result to PSI. */
internal fun EditorAssemblyCapture.assemblyHints(outcome: BackgroundAssemblyOutcome): MipsAssemblyHints =
    MipsAssemblyHints(hintVersion(), writtenAddresses(outcome.symbols), outcome.pseudoExpansions[source].orEmpty())

private fun EditorAssemblyCapture.writtenAddresses(symbols: AssemblySymbols?): List<MipsWrittenAddress> =
    symbols?.let {
        (it.localSymbols(source).asSequence() + it.symbols.globals().asSequence()).mapNotNull { (name, definition) ->
            ProgressManager.checkCanceled()
            val origin = definition.origin!!
            if (origin.caller != null || origin.location.file() != source) return@mapNotNull null
            MipsWrittenAddress(origin.location.line(), definition.column!!, name, definition.address)
        }.toList()
    }.orEmpty()

/** Publish only after the annotator has checked the source revisions and assembly context. */
internal fun MipsFile.publishAssemblyHints(snapshot: MipsAssemblyHints) {
    val previous = getUserData(assemblyHints)
    if (previous == snapshot || previous == null && snapshot.addresses.isEmpty() && snapshot.expansions.isEmpty()) return
    putUserData(assemblyHints, snapshot)
    project.service<MipsAssemblyInlayRefresh>().changed(virtualFile)
}

@Service(Service.Level.PROJECT)
internal class MipsAssemblyInlayRefresh(project: Project, scope: CoroutineScope) {
    private val refresh = DebouncedUpdates.forScope<VirtualFile?>(scope, "Refresh MARS assembly inlays", 100.milliseconds)
        .withContext(Dispatchers.EDT)
        .runBatchedDistinct { files ->
            for (editor in FileEditorManager.getInstance(project).allEditors.filterIsInstance<TextEditor>()) {
                if (editor.file.fileType == MipsFileType && (null in files || editor.file in files)) {
                    DeclarativeInlayHintsPassFactory.scheduleRecompute(editor.editor, project)
                }
            }
        }

    init {
        project.messageBus.connect(scope).subscribe(MipsAssemblyContextListener.TOPIC, MipsAssemblyContextListener { changed(null) })
    }

    fun changed(file: VirtualFile?) {
        val enabled = runReadActionBlocking {
            val settings = DeclarativeInlayHintsSettings.getInstance()
            settings.isProviderEnabled(MIPS_ADDRESS_HINTS_ID) == true || settings.isProviderEnabled(MIPS_EXPANSION_HINTS_ID) == true
        }
        if (enabled) refresh.queue(file)
    }

    @TestOnly
    fun waitForRefresh() = refresh.waitForAllExecuted(5.seconds)
}
