package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
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

internal class MipsAddressInlayProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): OwnBypassCollector? =
        AddressCollector.takeIf { file is MipsFile }
}

private object AddressCollector : OwnBypassCollector {
    override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
        val mips = file as MipsFile
        val snapshot = mips.getUserData(addressHints) ?: return
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(mips)!!
        if (!documents.isCommitted(document)) return
        val capture = collectEditorAssembly(mips) ?: return
        if (snapshot.version != capture.addressVersion()) return
        for (hint in snapshot.addresses) {
            ProgressManager.checkCanceled()
            val offset = document.getLineStartOffset(hint.line - 1) + hint.column - 1
            val declaration = MipsDeclaration.findAt(mips, offset, hint.name) ?: continue
            val position = InlineInlayPosition(declaration.textRange.endOffset, relatedToPrevious = true)
            sink.addPresentation(position, tooltip = bundle.getMessage("inlay.address.tooltip"), hintFormat = HintFormat.default) {
                text(Binary.intToHexString(hint.address))
            }
        }
    }
}

private val bundle = DynamicBundle(MipsAddressInlayProvider::class.java, "messages.MarsBundle")
private val addressHints = Key.create<MipsAddressHints>(MIPS_ADDRESS_HINTS_ID)

internal data class MipsAddressHintVersion(
    val sources: MarsSourceSet, val handler: Path?, val options: MarsExecutionOptions,
    val documents: Map<Path, Long>, val structure: Long,
)

internal data class MipsWrittenAddress(val line: Int, val column: Int, val name: String, val address: Int)

internal data class MipsAddressHints(val version: MipsAddressHintVersion, val addresses: List<MipsWrittenAddress>)

private fun EditorAssemblyCapture.addressVersion() =
    MipsAddressHintVersion(sources.sourceSet, sources.exceptionHandler, options, sources.revisions, structureStamp)

/** Called in the detached assembly pass, before applying the result to PSI. */
internal fun EditorAssemblyCapture.addressHints(symbols: AssemblySymbols?): MipsAddressHints {
    val addresses = symbols?.let {
        (it.localSymbols(source).asSequence() + it.symbols.globals().asSequence()).mapNotNull { (name, definition) ->
            ProgressManager.checkCanceled()
            val origin = definition.origin()!!
            if (origin.caller() != null || origin.location().file() != source) return@mapNotNull null
            MipsWrittenAddress(origin.location().line(), definition.column()!!, name, definition.address())
        }.toList()
    }.orEmpty()
    return MipsAddressHints(addressVersion(), addresses)
}

/** Publish only after the annotator has checked the source revisions and assembly context. */
internal fun MipsFile.publishAddressHints(snapshot: MipsAddressHints) {
    val previous = getUserData(addressHints)
    if (previous == snapshot || previous == null && snapshot.addresses.isEmpty()) return
    putUserData(addressHints, snapshot)
    project.service<MipsAddressHintRefresh>().changed(virtualFile)
}

@Service(Service.Level.PROJECT)
internal class MipsAddressHintRefresh(project: Project, scope: CoroutineScope) {
    private val refresh = DebouncedUpdates.forScope<VirtualFile?>(scope, "Refresh MARS address inlays", 100.milliseconds)
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
            DeclarativeInlayHintsSettings.getInstance().isProviderEnabled(MIPS_ADDRESS_HINTS_ID) == true
        }
        if (enabled) refresh.queue(file)
    }

    @TestOnly
    fun waitForRefresh() = refresh.waitForAllExecuted(5.seconds)
}
