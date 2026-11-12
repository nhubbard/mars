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
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.TaskRunnerResults
import com.intellij.util.containers.SLRUMap
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsAssemblyContextListener
import edu.missouristate.mars.plugin.analysis.assembleIn
import edu.missouristate.mars.plugin.analysis.captureBuildSources
import edu.missouristate.mars.plugin.run.MarsExecutionSources
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.asCompletableFuture
import org.jetbrains.concurrency.asPromise

internal val buildMessages = DynamicBundle(MarsBuildService::class.java, "messages.MarsBundle")

internal class MarsAssembledProgram(val image: AssemblyImage, val sources: CapturedAssemblySources)

@Service(Service.Level.PROJECT)
internal class MarsBuildService(private val project: Project, private val scope: CoroutineScope) {
    private data class Revision(
        val snapshot: AssemblySourceSnapshot,
        val exceptionHandler: Path?,
        val options: MarsExecutionOptions,
    )

    private data class BuildOutput(
        val diagnostics: List<AssemblyError>,
        val symbols: AssemblySymbolMap?,
        val image: AssemblyImage? = null,
        val reused: Boolean = false,
    ) {
        val succeeded get() = symbols != null
    }

    // Use content and resolved include identities. Fresh document stamps belong to each caller.
    private val images = SLRUMap<Revision, BuildOutput>(2, 2)

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

    internal suspend fun assemble(sourceSet: MarsSourceSet): Boolean =
        buildImage(sourceSet, projectOptions, activateBuildView = true, reuse = false) != null

    internal suspend fun prepare(
        sources: MarsSourceSet, options: MarsExecutionOptions, activateBuildView: Boolean,
    ): MarsAssembledProgram? = buildImage(sources, options, activateBuildView, reuse = true)

    internal suspend fun assembleOpened(sources: MarsSourceSet) {
        buildImage(sources, projectOptions, activateBuildView = false, reuse = true, activateOnFailure = false)
    }

    private suspend fun buildImage(
        sourceSet: MarsSourceSet, options: MarsExecutionOptions, activateBuildView: Boolean, reuse: Boolean,
        activateOnFailure: Boolean = true,
    ): MarsAssembledProgram? {
        val (capture, output) = buildWithProgress(sourceSet, options, activateBuildView, activateOnFailure) { sources ->
            val revision = Revision(sources.snapshot, sources.exceptionHandler, options)
            val cached = if (reuse) synchronized(images) { images.get(revision) } else null
            cached?.copy(reused = true) ?: assembleImage(sources, options)
        }
        val image = output.image ?: return null
        checkCanceled()
        if (!output.reused) {
            synchronized(images) { images.put(Revision(capture.snapshot, capture.exceptionHandler, options), output) }
        }
        return MarsAssembledProgram(image, capture)
    }

    private suspend fun assembleImage(sources: CapturedAssemblySources, options: MarsExecutionOptions): BuildOutput {
        val component = MarsSessionService.createMarsComponent()
        val context = currentCoroutineContext()
        return component.context().use { session ->
            val result = assembleInto(sources, options, component)
            if (result.succeeded) result.copy(image = session.captureAssemblyImage(context::ensureActive)) else result
        }
    }

    private val projectOptions get() = project.service<MarsAssemblySettings>().executionOptions

    internal suspend fun assemble(
        entry: Path, component: MarsComponent, activateBuildView: Boolean, options: MarsExecutionOptions = projectOptions,
    ): Boolean = buildWithProgress(MarsSourceSet(entry), options, activateBuildView) { sources ->
        assembleInto(sources, options, component)
    }.second.succeeded

    private suspend fun buildWithProgress(
        sourceSet: MarsSourceSet, options: MarsExecutionOptions, activateBuildView: Boolean,
        activateOnFailure: Boolean = true,
        assemble: suspend (CapturedAssemblySources) -> BuildOutput,
    ): Pair<CapturedAssemblySources, BuildOutput> {
        val entry = sourceSet.entry
        val symbolBuild = project.service<MarsAnalysisService>().beginSymbolBuild(sourceSet, options)
        val descriptor = DefaultBuildDescriptor(
            Any(), buildMessages.getMessage("build.title", entry.fileName.toString()),
            entry.toAbsolutePath().parent.toString(), System.currentTimeMillis(),
        ).apply {
            isActivateToolWindowWhenAdded = activateBuildView
            isActivateToolWindowWhenFailed = activateOnFailure
        }
        val progressDescriptor = object : BuildProgressDescriptor {
            override fun getTitle() = descriptor.title
            override fun getBuildDescriptor() = descriptor
        }
        val progress = BuildViewManager.createBuildProgress(project).start(progressDescriptor.title, progressDescriptor)
        var cancelled = false
        var succeeded = false
        var reused = false
        try {
            val completed = assembleAndReport(symbolBuild, assemble) { diagnostics, capture ->
                val events = readAction { diagnosticEvents(project, progress.id, diagnostics, capture) }
                checkCanceled()
                val view = project.getService(BuildViewManager::class.java)
                for (event in events) {
                    checkCanceled()
                    view.onEvent(progress.id, event)
                }
            }
            succeeded = completed.second.succeeded
            reused = completed.second.reused
            return completed
        } catch (cancel: CancellationException) {
            cancelled = true
            throw cancel
        } finally {
            finish(progress, cancelled, succeeded, reused)
        }
    }

    internal suspend fun assembleForCompiler(
        sources: MarsSourceSet, report: suspend (List<AssemblyError>, CapturedAssemblySources) -> Unit,
    ): Boolean {
        val options = projectOptions
        val component = MarsSessionService.createMarsComponent()
        val symbolBuild = project.service<MarsAnalysisService>().beginSymbolBuild(sources, options)
        return component.context().use {
            assembleAndReport(symbolBuild, { capture -> assembleInto(capture, options, component) }, report).second.succeeded
        }
    }

    private suspend fun assembleInto(
        sources: CapturedAssemblySources, options: MarsExecutionOptions, component: MarsComponent,
    ): BuildOutput {
        options.applyForAssembly(component.context())
        val result = sources.assembleIn(component)
        return BuildOutput(result.errors(), if (result.succeeded()) result.program()!!.symbolMap() else null)
    }

    private suspend fun assembleAndReport(
        symbolBuild: MarsAnalysisService.SymbolBuild, assemble: suspend (CapturedAssemblySources) -> BuildOutput,
        report: suspend (List<AssemblyError>, CapturedAssemblySources) -> Unit,
    ): Pair<CapturedAssemblySources, BuildOutput> {
        val analysis = project.service<MarsAnalysisService>()
        val capture = captureBuildSources(symbolBuild.selection.sourceSet, project.service<MarsAssemblySettings>().exceptionHandler)
        checkCanceled()
        analysis.publishBuiltSymbols(symbolBuild, capture, AssemblySymbolMap.EMPTY)
        DaemonCodeAnalyzer.getInstance(project).restart("MARS assembly entry selected")
        project.messageBus.syncPublisher(MipsAssemblyContextListener.TOPIC).assemblyEntryChanged()
        val result = assemble(capture)
        checkCanceled()
        report(result.diagnostics, capture)
        checkCanceled()
        result.symbols?.let { analysis.publishBuiltSymbols(symbolBuild, capture, it) }
        return capture to result
    }

    private fun finish(
        progress: BuildProgress<BuildProgressDescriptor>, cancelled: Boolean, succeeded: Boolean, reused: Boolean,
    ) {
        when {
            cancelled -> progress.cancel()
            succeeded -> progress.finish(reused)
            else -> progress.fail()
        }
    }
}

/** Called under a read action so revisions and positions refer to the same document state. */
internal fun diagnosticEvents(
    project: Project, buildId: Any, diagnostics: List<AssemblyError>, capture: CapturedAssemblySources,
): List<MessageEvent> {
    val sources = MarsExecutionSources(capture)
    return diagnostics.map { diagnosticEvent(project, buildId, it, capture, sources) }
}

internal fun diagnosticEvent(
    project: Project, buildId: Any, diagnostic: AssemblyError, capture: CapturedAssemblySources,
    sources: MarsExecutionSources = MarsExecutionSources(capture),
): MessageEvent {
    val kind = if (diagnostic.isWarning) MessageEvent.Kind.WARNING else MessageEvent.Kind.ERROR
    val builder = MessageEvent.builder(diagnostic.message(), kind).withParentId(buildId)
    val path = diagnostic.file() ?: return builder.build()
    if (diagnostic.line() < 1) return builder.withHint(path.toString()).build()
    val location = Location(path, diagnostic.line())
    val target = sources.currentLineDescriptor(project, location) ?: return builder.withHint(path.toString()).build()
    val editorFile = target.file
    val document = FileDocumentManager.getInstance().getDocument(editorFile)!!
    val line = diagnostic.line() - 1
    // Live text positions carry an offset. Captured views carry an editor line, which may be relative to an excerpt.
    if (target.line < 0 && document.modificationStamp != capture.revisions[path]) {
        return builder.withHint(path.toString()).build()
    }
    val editorLine = target.line.takeIf { it >= 0 } ?: line
    val start = document.getLineStartOffset(editorLine)
    val column = (diagnostic.column() - 1).coerceIn(0, document.getLineEndOffset(editorLine) - start)
    sources.rememberEntryFor(editorFile, location)
    return builder.withFilePosition(FilePosition(path, line, column))
        .withNavigatable(OpenFileDescriptor(project, editorFile, start + column))
        .build()
}
