package edu.missouristate.mars.plugin.build

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiManager
import com.intellij.util.ui.update.DebouncedUpdates
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.inferredMipsAssemblyEntry
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.annotations.TestOnly

internal class MarsOpenAssemblyListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val project = source.project
        if (file.isLocalSource() && file.fileType == MipsFileType && project.service<MarsAssemblySettings>().assembleOnOpen) {
            project.service<MarsOpenAssembly>().queue(file)
        }
    }
}

@Service(Service.Level.PROJECT)
internal class MarsOpenAssembly(private val project: Project, scope: CoroutineScope) {
    private val updates = DebouncedUpdates.forScope<VirtualFile>(scope, "MARS assembly on open", 300.milliseconds)
        .withContext(Dispatchers.Default)
        .runBatchedDistinct { files: Set<VirtualFile> -> assemble(files) }

    fun queue(file: VirtualFile) = updates.queue(file)

    @TestOnly
    suspend fun awaitPending() = updates.awaitAllExecuted()

    private suspend fun assemble(files: Set<VirtualFile>) {
        val editors = FileEditorManager.getInstance(project)
        val sourceSets = constrainedReadAction(ReadConstraint.inSmartMode(project), ReadConstraint.withDocumentsCommitted(project)) {
            if (!project.service<MarsAssemblySettings>().assembleOnOpen || !TrustedProjects.isProjectTrusted(project)) {
                return@constrainedReadAction emptySet()
            }
            val psi = PsiManager.getInstance(project)
            val analysis = project.service<MarsAnalysisService>()
            files.mapNotNullTo(linkedSetOf()) { file ->
                if (!editors.isFileOpen(file)) return@mapNotNullTo null
                val source = psi.findFile(file) as? MipsFile ?: return@mapNotNullTo null
                analysis.selectedAssembly(source)?.sourceSet
                    ?: inferredMipsAssemblyEntry(source)?.let { MarsSourceSet(it) }
            }
        }
        if (sourceSets.isEmpty()) return
        withBackgroundProgress(project, buildMessages.getMessage("build.progress")) {
            val build = project.service<MarsBuildService>()
            for (sources in sourceSets) build.assembleOpened(sources)
        }
    }
}
