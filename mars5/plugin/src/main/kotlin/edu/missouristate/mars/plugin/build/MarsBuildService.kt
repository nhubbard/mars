package edu.missouristate.mars.plugin.build

import com.intellij.DynamicBundle
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblyResult
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.analysis.captureAssemblySources
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
internal class MarsBuildService(private val project: Project, private val scope: CoroutineScope) {
    fun assembleCurrentFile(entry: Path) = scope.launch(Dispatchers.Default) {
        withBackgroundProgress(project, bundle.getMessage("build.progress")) { assemble(entry) }
    }

    internal suspend fun assemble(entry: Path) {
        val descriptor = DefaultBuildDescriptor(
            Any(), bundle.getMessage("build.title", entry.fileName.toString()),
            entry.toAbsolutePath().parent.toString(), System.currentTimeMillis(),
        ).apply { isActivateToolWindowWhenAdded = true }
        val progressDescriptor = object : BuildProgressDescriptor {
            override fun getTitle() = descriptor.title
            override fun getBuildDescriptor() = descriptor
        }
        val progress = BuildViewManager.createBuildProgress(project).start(progressDescriptor.title, progressDescriptor)
        var cancelled = false
        var succeeded = false
        try {
            val capture = captureAssemblySources(listOf(entry), null)
            checkCanceled()
            val component = MarsSessionService.createMarsComponent()
            val result = component.context().use { capture.assembleIn(component) }
            checkCanceled()
            val events = readAction {
                result.errors().map { diagnosticEvent(project, progress.id, it, capture.revisions) }
            }
            checkCanceled()
            val view = project.getService(BuildViewManager::class.java)
            for (event in events) {
                checkCanceled()
                view.onEvent(progress.id, event)
            }
            checkCanceled()
            succeeded = result.succeeded()
        } catch (cancel: CancellationException) {
            cancelled = true
            throw cancel
        } finally {
            finish(progress, cancelled, succeeded)
        }
    }

    private fun finish(progress: BuildProgress<BuildProgressDescriptor>, cancelled: Boolean, succeeded: Boolean) {
        when {
            cancelled -> progress.cancel()
            succeeded -> progress.finish()
            else -> progress.fail()
        }
    }

    companion object {
        private val bundle = DynamicBundle(MarsBuildService::class.java, "messages.MarsBundle")
    }
}

internal suspend fun CapturedAssemblySources.assembleIn(component: MarsComponent): AssemblyResult {
    val settings = component.simulationSettings()
    val context = currentCoroutineContext()
    return component.assembler().assembleSnapshot(
        snapshot, exceptionHandler, settings.isExtendedAssemblerEnabled, settings.isWarningsAreErrors,
        context::ensureActive,
    )
}

/** Called under a read action so the stamp and position are checked against the same document. */
internal fun diagnosticEvent(
    project: Project, buildId: Any, diagnostic: AssemblyError, revisions: Map<Path, Long>,
): MessageEvent {
    val kind = if (diagnostic.isWarning) MessageEvent.Kind.WARNING else MessageEvent.Kind.ERROR
    val builder = MessageEvent.builder(diagnostic.message(), kind).withParentId(buildId)
    val path = diagnostic.file() ?: return builder.build()
    val file = VirtualFileManager.getInstance().findFileByNioPath(path)
    val document = file?.let(FileDocumentManager.getInstance()::getDocument)
    val line = diagnostic.line() - 1
    if (document == null || document.modificationStamp != revisions[path] || line < 0 || line >= document.lineCount) {
        return builder.withHint(path.toString()).build()
    }
    val start = document.getLineStartOffset(line)
    val column = (diagnostic.column() - 1).coerceIn(0, document.getLineEndOffset(line) - start)
    return builder.withFilePosition(FilePosition(path, line, column))
        .withNavigatable(OpenFileDescriptor(project, file, start + column))
        .build()
}
