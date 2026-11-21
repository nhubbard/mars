package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParseException
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.OSAgnosticPathUtil.expandUserHome
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.panel
import edu.missouristate.mars.plugin.dynamicBundleOf
import java.awt.Dimension
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val packageMessages = dynamicBundleOf<MarsLessonPackageImportAction>("messages.MarsLessonPackageBundle")

internal class MarsLessonPackageImportAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = event.project?.basePath != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val root = project.basePath ?: return
        val source = MarsLessonPackageFileDialog(project)
        source.show()
        if (!source.isOK) return
        val archive = source.archive
        try {
            val prepared = runWithModalProgressBlocking(ModalTaskOwner.project(project), packageMessages.getMessage("import.inspect")) {
                withContext(Dispatchers.IO) { MarsLessonPackages.prepare(archive) }
            }
            prepared.use {
                val preview = MarsLessonPackagePreviewDialog(project, prepared)
                preview.show()
                if (!preview.isOK) return
                runWithModalProgressBlocking(ModalTaskOwner.project(project), packageMessages.getMessage("import.install")) {
                    val installed = withContext(Dispatchers.IO) { prepared.install(Path.of(root)) }
                    openInstalledPackage(project, installed)
                }
            }
        } catch (error: IOException) { showFailure(project, error) }
        catch (error: IllegalArgumentException) { showFailure(project, error) }
        catch (error: JsonParseException) { showFailure(project, error) }
    }

    private fun showFailure(project: Project, error: Exception) = Messages.showErrorDialog(
        project, packageImportFailure(error), packageMessages.getMessage("import.error.title"),
    )
}

internal fun packageImportFailure(error: Exception): String = error.message ?: packageMessages.getMessage("import.error.unknown")

/** Also suitable for reopening an installed working copy; missing student files are reported. */
internal suspend fun openInstalledPackage(project: Project, installed: MarsLessonPackages.Installed) {
    val files = withContext(Dispatchers.IO) {
        listOf(installed.assignment, installed.entry).map {
            VirtualFileManager.getInstance().refreshAndFindFileByNioPath(it)
                ?: throw IOException(packageMessages.getMessage("import.file.missing", it.toString()))
        }
    }
    withContext(Dispatchers.EDT) {
        val editors = FileEditorManager.getInstance(project)
        for ((index, file) in files.withIndex()) editors.openFile(file, FileEditorOpenRequest.withFocus(index == 1))
    }
}

/** Native path entry and Browse use the same chooser as other IDE file settings. */
internal class MarsLessonPackageFileDialog(project: Project) : DialogWrapper(project) {
    val archiveField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withExtensionFilter("zip")
            .withTitle(packageMessages.getMessage("import.choose")))
        textField.accessibleContext.accessibleName = packageMessages.getMessage("import.path")
    }
    val archive: Path get() = Path.of(expandUserHome(archiveField.text.trim()))
    init {
        Disposer.register(disposable, archiveField)
        title = packageMessages.getMessage("import.choose")
        setOKButtonText(packageMessages.getMessage("import.preview"))
        init()
    }
    override fun createCenterPanel(): JComponent = panel {
        row(packageMessages.getMessage("import.path")) { cell(archiveField).resizableColumn() }
        row { comment(packageMessages.getMessage("import.choose.help")) }
    }.apply { preferredSize = Dimension(650, preferredSize.height) }
    override fun getPreferredFocusedComponent(): JComponent = archiveField.textField
    public override fun doValidate(): ValidationInfo? = try {
        if (archive.isAbsolute) null else ValidationInfo(packageMessages.getMessage("import.path.invalid"), archiveField)
    } catch (_: InvalidPathException) { ValidationInfo(packageMessages.getMessage("import.path.invalid"), archiveField) }
}

internal class MarsLessonPackagePreviewDialog(project: Project, prepared: MarsLessonPackages.Prepared) : DialogWrapper(project) {
    val report: String = packageMessages.getMessage("import.summary", prepared.metadata.title, prepared.metadata.id,
        prepared.metadata.version, prepared.metadata.isa, prepared.sha256)
    init {
        title = packageMessages.getMessage("import.confirm")
        setOKButtonText(packageMessages.getMessage("import.accept"))
        init()
    }
    override fun createCenterPanel(): JComponent = JBScrollPane(JBTextArea(report).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = packageMessages.getMessage("import.confirm")
        caretPosition = 0
    }).apply { preferredSize = Dimension(700, 300) }
}
