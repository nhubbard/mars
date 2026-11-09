package edu.missouristate.mars.plugin.build

import com.intellij.DynamicBundle
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.TaskRunnerResults
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsAssemblyContextListener
import edu.missouristate.mars.plugin.analysis.assembleIn
import edu.missouristate.mars.plugin.analysis.captureBuildSources
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import org.jetbrains.concurrency.asPromise

internal val buildMessages = DynamicBundle(MarsBuildService::class.java, "messages.MarsBundle")

internal class MarsAssembledProgram(val session: MarsContext, val sources: Set<Path>) : AutoCloseable {
    override fun close() = session.close()
}

@Service(Service.Level.PROJECT)
internal class MarsBuildService(private val project: Project, private val scope: CoroutineScope) {
    fun assembleTasks(tasks: List<MarsBuildTask>) = scope.async<ProjectTaskRunner.Result>(Dispatchers.Default) {
        var result = TaskRunnerResults.SUCCESS
        withBackgroundProgress(project, buildMessages.getMessage("build.progress")) {
            for (task in tasks.distinctBy(MarsBuildTask::sources)) {
                if (!assemble(task.sources)) result = TaskRunnerResults.FAILURE
            }
        }
        result
    }.asCompletableFuture().asPromise()

    internal suspend fun assemble(entry: Path) = assemble(MarsSourceSet(entry))

    internal suspend fun assemble(sourceSet: MarsSourceSet): Boolean {
        val component = MarsSessionService.createMarsComponent()
        return component.context().use { assemble(sourceSet, component, activateBuildView = true) }
    }

    internal suspend fun prepare(
        sources: MarsSourceSet, options: MarsExecutionOptions, activateBuildView: Boolean,
    ): MarsAssembledProgram? {
        val component = MarsSessionService.createMarsComponent()
        val session = component.context()
        var program: MarsAssembledProgram? = null
        try {
            var paths = emptySet<Path>()
            if (assemble(sources, component, activateBuildView, options) { paths = it + sources.entry }) {
                program = MarsAssembledProgram(session, paths)
            }
            return program
        } finally {
            if (program == null) session.close()
        }
    }

    internal suspend fun assemble(
        entry: Path, component: MarsComponent, activateBuildView: Boolean, options: MarsExecutionOptions = MarsExecutionOptions(),
    ) = assemble(MarsSourceSet(entry), component, activateBuildView, options)

    internal suspend fun assemble(
        sourceSet: MarsSourceSet, component: MarsComponent, activateBuildView: Boolean, options: MarsExecutionOptions = MarsExecutionOptions(),
        onSourcesCaptured: (Set<Path>) -> Unit = {},
    ): Boolean {
        val entry = sourceSet.entry
        options.applyTo(component.context().settings)
        val analysis = project.service<MarsAnalysisService>()
        val symbolBuild = analysis.beginSymbolBuild(sourceSet, options)
        val descriptor = DefaultBuildDescriptor(
            Any(), buildMessages.getMessage("build.title", entry.fileName.toString()),
            entry.toAbsolutePath().parent.toString(), System.currentTimeMillis(),
        ).apply { isActivateToolWindowWhenAdded = activateBuildView }
        val progressDescriptor = object : BuildProgressDescriptor {
            override fun getTitle() = descriptor.title
            override fun getBuildDescriptor() = descriptor
        }
        val progress = BuildViewManager.createBuildProgress(project).start(progressDescriptor.title, progressDescriptor)
        var cancelled = false
        var succeeded = false
        try {
            val capture = captureBuildSources(sourceSet, project.service<MarsAssemblySettings>().exceptionHandler)
            onSourcesCaptured(capture.snapshot.texts().keys)
            checkCanceled()
            analysis.publishBuiltSymbols(symbolBuild, capture, AssemblySymbolMap.EMPTY)
            DaemonCodeAnalyzer.getInstance(project).restart("MARS assembly entry selected")
            project.messageBus.syncPublisher(MipsAssemblyContextListener.TOPIC).assemblyEntryChanged()
            val result = capture.assembleIn(component)
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
            if (succeeded) analysis.publishBuiltSymbols(symbolBuild, capture, result.program().symbolMap())
            return succeeded
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
