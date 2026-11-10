package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import edu.missouristate.mars.api.AssembledInstruction
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.EditorAssemblyCapture
import edu.missouristate.mars.plugin.analysis.assembleIn
import edu.missouristate.mars.plugin.analysis.collectEditorAssembly
import edu.missouristate.mars.plugin.build.isLocalSource
import edu.missouristate.mars.util.Binary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MarsShowExpansionAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (file, editor) = target(e) ?: return
        val project = file.project
        val sourceText = editor.document.immutableCharSequence
        val line = editor.document.getLineNumber(editor.caretModel.offset) + 1
        e.coroutineScope.launch(Dispatchers.Default) {
            val preview = withBackgroundProgress(project, messages.getMessage("expansion.progress")) {
                inspectExpansion(file, line, sourceText)
            }
            withContext(Dispatchers.EDT) { showExpansionPreview(project, editor, preview) }
        }
    }

    private fun target(e: AnActionEvent): Pair<MipsFile, Editor>? {
        val file = e.getData(CommonDataKeys.PSI_FILE) as? MipsFile ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        return (file to editor).takeIf { file.viewProvider.virtualFile.isLocalSource() }
    }
}

internal fun showExpansionPreview(project: Project, editor: Editor, preview: ExpansionPreview) {
    if (editor.isDisposed) return
    when (preview) {
        is ExpansionPreview.Instructions ->
            JBPopupFactory.getInstance().createListPopup(instructionStep(project, editor, preview))
                .showInBestPositionFor(editor)
        is ExpansionPreview.Unavailable ->
            HintManager.getInstance().showErrorHint(editor, messages.getMessage(preview.messageKey))
    }
}

internal sealed interface ExpansionPreview {
    data class Instructions(val capture: EditorAssemblyCapture, val rows: List<AssembledInstruction>) : ExpansionPreview
    enum class Unavailable(val messageKey: String) : ExpansionPreview {
        ENTRY("expansion.unavailable.entry"),
        CHANGED("expansion.unavailable.changed"),
        ASSEMBLY("expansion.unavailable.assembly"),
        EMPTY("expansion.unavailable.empty"),
    }
}

internal suspend fun inspectExpansion(file: MipsFile, line: Int, sourceText: CharSequence): ExpansionPreview {
    val smart = ReadConstraint.inSmartMode(file.project)
    val capture = constrainedReadAction(smart) { collectEditorAssembly(file) } ?: return ExpansionPreview.Unavailable.ENTRY
    if (!StringUtil.equals(sourceText, capture.sources.snapshot.texts()[capture.source])) {
        return ExpansionPreview.Unavailable.CHANGED
    }
    return capture.expansionAt(file, line)
}

internal suspend fun EditorAssemblyCapture.expansionAt(file: MipsFile, line: Int): ExpansionPreview {
    val component = MarsSessionService.createMarsComponent()
    val cancellation = currentCoroutineContext()
    val rows = component.context().use { session ->
        options.applyForAssembly(session)
        val result = sources.assembleIn(component)
        result.program()?.instructionsAt(Location(source, line), cancellation::ensureActive)
    }
    val valid = constrainedReadAction(ReadConstraint.inSmartMode(file.project)) {
        val current = collectEditorAssembly(file)
        // A filesystem refresh can replace cached documents while preserving the assembled inputs.
        current?.let { Triple(it.options, it.sources.snapshot, it.sources.exceptionHandler) } ==
            Triple(options, sources.snapshot, sources.exceptionHandler)
    }
    if (!valid) return ExpansionPreview.Unavailable.CHANGED
    if (rows == null) return ExpansionPreview.Unavailable.ASSEMBLY
    if (rows.isEmpty()) return ExpansionPreview.Unavailable.EMPTY
    return ExpansionPreview.Instructions(this, rows)
}

internal fun instructionStep(project: Project, editor: Editor, preview: ExpansionPreview.Instructions) =
    object : BaseListPopupStep<AssembledInstruction>(messages.getMessage("expansion.title"), preview.rows) {
        override fun getTextFor(value: AssembledInstruction): String =
            "${Binary.intToHexString(value.address())}  ${Binary.intToHexString(value.encoding())}  ${value.assembly()}"

        override fun hasSubstep(selectedValue: AssembledInstruction) = true

        override fun onChosen(selectedValue: AssembledInstruction, finalChoice: Boolean) =
            originStep(project, editor, preview.capture, selectedValue.origin())
    }

private fun originStep(project: Project, editor: Editor, capture: EditorAssemblyCapture, origin: Origin) =
    object : BaseListPopupStep<Origin>(
        messages.getMessage("expansion.origin.title"), generateSequence(origin, Origin::caller).toList(),
    ) {
        override fun getTextFor(value: Origin): String {
            val location = value.location()
            return "${location.file()}:${location.line()}"
        }

        override fun onChosen(selectedValue: Origin, finalChoice: Boolean) = doFinalStep {
            val target = expansionSourceTarget(project, capture, selectedValue.location())
            if (target == null) HintManager.getInstance().showErrorHint(editor, messages.getMessage("expansion.unavailable.changed"))
            else target.navigate(true)
        }
    }

internal fun expansionSourceTarget(project: Project, capture: EditorAssemblyCapture, location: Location): OpenFileDescriptor? {
    val file = VirtualFileManager.getInstance().findFileByNioPath(location.file()) ?: return null
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
    if (!StringUtil.equals(document.immutableCharSequence, capture.sources.snapshot.texts()[location.file()]) ||
        location.line() > document.lineCount
    ) return null
    return OpenFileDescriptor(project, file, document.getLineStartOffset(location.line() - 1))
}

private val messages = DynamicBundle(MarsShowExpansionAction::class.java, "messages.MarsBundle")
