package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import edu.missouristate.mars.ApplicationInfo
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblyResult
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService.AssemblySelection
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsAssemblyMode
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.references.findMipsIncludeFile
import edu.missouristate.mars.plugin.language.references.mipsIncludeDirectoryPath
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Map.copyOf as copyOfMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CapturedAssemblySources(
    val snapshot: AssemblySourceSnapshot,
    val exceptionHandler: Path?,
    val revisions: Map<Path, Long>,
    val rootsBySource: Map<Path, Path>,
    val directoryStamp: Long? = null,
    val sourceSet: MarsSourceSet = MarsSourceSet(snapshot.entries(), MarsAssemblyMode.SINGLE),
)

internal data class EditorAssemblyCapture(
    val source: Path,
    val selectedAssembly: AssemblySelection?,
    val sources: CapturedAssemblySources,
    val options: MarsExecutionOptions,
    val structureStamp: Long,
) {
    fun validate(file: MipsFile, accept: (Path, Document, Long) -> Boolean): Boolean {
        if (VirtualFileManager.getInstance().structureModificationCount != structureStamp) return false
        val settings = file.project.service<MarsAssemblySettings>()
        if (sources.exceptionHandler != settings.exceptionHandler) return false
        val selected = file.project.service<MarsAnalysisService>().selectedAssembly(file)
        if (selected != selectedAssembly) return false
        if (options != effectiveOptions(file, selected)) return false
        val entry = sources.sourceSet.entry
        if (entry != selected?.entry && entry != inferredMipsAssemblyEntry(file)) return false
        return sources.revisions.validateSourceDocuments(accept)
    }
}

/** Chooses the same assembly entry for editor diagnostics and on-demand inspection. */
internal fun collectEditorAssembly(file: MipsFile): EditorAssemblyCapture? {
    val virtualFile = file.viewProvider.virtualFile
    if (!virtualFile.isInLocalFileSystem) return null
    val source = virtualFile.toNioPath().toAbsolutePath().normalize()
    val selected = file.project.service<MarsAnalysisService>().selectedAssembly(file)
    val sourceSet = selected?.sourceSet ?: MarsSourceSet(inferredMipsAssemblyEntry(file) ?: return null)
    val sources = collectBackgroundAssemblySources(file.project, sourceSet)
    return EditorAssemblyCapture(source, selected, sources, effectiveOptions(file, selected),
        VirtualFileManager.getInstance().structureModificationCount)
}

private fun effectiveOptions(file: MipsFile, selection: AssemblySelection?): MarsExecutionOptions =
    selection?.options ?: file.project.service<MarsAssemblySettings>().executionOptions

private data class SourceDocument(val text: String, val revision: Long, val identity: Path)

private data class IncludeCursor(val path: Path, val lines: Iterator<String>)

/**
 * Capture one coherent document revision before handing off to the Java assembler.
 * The include prepass reads the captured text directly, including files with no MARS file type.
 */
internal suspend fun captureAssemblySources(entries: List<Path>, exceptionHandler: Path?): CapturedAssemblySources =
    readAction { collectAssemblySources(entries, exceptionHandler) }

internal suspend fun captureBuildSources(sourceSet: MarsSourceSet, exceptionHandler: Path? = null): CapturedAssemblySources =
    readAction { collectBuildSources(sourceSet, exceptionHandler) }

private fun collectBuildSources(
    sourceSet: MarsSourceSet, exceptionHandler: Path?, onDependency: (Any) -> Unit = {},
): CapturedAssemblySources {
    val root = sourceSet.entry
    val separateRoots = setOfNotNull(root, exceptionHandler)
    val vfs = VirtualFileManager.getInstance()
    if (sourceSet.mode != MarsAssemblyMode.DIRECTORY) {
        val ordered = listOf(root) + sourceSet.entries.drop(1).filterNot { it in separateRoots }
        return collectAssemblySources(ordered, exceptionHandler, onDependency).copy(sourceSet = sourceSet)
    }
    val siblings = vfs.findFileByNioPath(root.parent)?.children.orEmpty().asSequence()
        .filter { it.isAssemblyDirectorySource() }
        .map { it.toNioPath().toAbsolutePath().normalize() }
        .filterNot { it in separateRoots }
        .sortedBy { it.fileName.toString() }
        .toList()
    return collectAssemblySources(listOf(root) + siblings, exceptionHandler, onDependency)
        .copy(directoryStamp = vfs.structureModificationCount, sourceSet = sourceSet)
}

internal fun VirtualFile.isAssemblyDirectorySource(): Boolean =
    !isDirectory && extension?.lowercase() in ApplicationInfo.fileExtensions

/** Called with read access by Build or by IntelliJ's external highlighting pass. */
internal fun collectAssemblySources(
    entries: List<Path>, exceptionHandler: Path?, onDependency: (Any) -> Unit = {},
): CapturedAssemblySources {
    val ordered = entries.map { it.toAbsolutePath().normalize() }
    val handler = exceptionHandler?.toAbsolutePath()?.normalize()
    val files = VirtualFileManager.getInstance()
    val documents = FileDocumentManager.getInstance()
    val captured = HashMap<Path, SourceDocument>()
    val rootsBySource = (ordered + listOfNotNull(handler)).associateWithTo(HashMap()) { it }
    val resolvedIncludes = HashMap<Path, Path>()
    val tokenizer = Tokenizer()

    fun capture(path: Path): SourceDocument? {
        captured[path]?.let { return it }
        val file = files.findFileByNioPath(path) ?: return null
        val document = documents.getDocument(file)
        onDependency(document ?: file)
        if (document == null) return null
        // Directory aliases share an include location. A file symlink keeps its own name and directory.
        val identity = path.fileSystem.getPath(file.mipsIncludeDirectoryPath()!!).resolve(file.name)
        return SourceDocument(document.text, document.modificationStamp, identity).also { captured[path] = it }
    }

    for (root in (listOfNotNull(handler) + ordered).distinct()) {
        ProgressManager.checkCanceled()
        val seen = HashSet<Path>()
        val pending = ArrayDeque<IncludeCursor>()
        fun enter(path: Path) {
            val source = capture(path) ?: return
            rootsBySource.putIfAbsent(path, root)
            if (seen.add(source.identity)) pending.addLast(IncludeCursor(path, source.text.lineSequence().iterator()))
        }

        // Match the assembler's depth-first order and independent include set for each entry.
        enter(root)
        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val (path, lines) = pending.last()
            if (!lines.hasNext()) {
                pending.removeLast()
                continue
            }
            val include = tokenizer.findIncludePath(lines.next()) ?: continue
            try {
                val request = path.resolveSibling(include)
                val target = files.findMipsIncludeFile(request) ?: continue
                val resolved = target.toNioPath().toAbsolutePath().normalize()
                resolvedIncludes[request] = resolved
                enter(resolved)
            } catch (_: InvalidPathException) {
                // The assembler reports the malformed path at the captured include site.
            }
        }
    }
    val snapshot = AssemblySourceSnapshot(
        ordered, captured.mapValues { it.value.text }, resolvedIncludes, captured.mapValues { it.value.identity },
    )
    return CapturedAssemblySources(snapshot, handler, copyOfMap(captured.mapValues { it.value.revision }), copyOfMap(rootsBySource))
}

/** Highlighting can reuse capture across editors until an input document or the VFS structure changes. */
private val captureKeys = MarsAssemblyMode.entries.associateWith { mode ->
    Key.create<CachedValue<CapturedAssemblySources>>("mars.assembly.sources.${mode.name}")
}

internal fun collectBackgroundAssemblySources(project: Project, sourceSet: MarsSourceSet): CapturedAssemblySources {
    val settings = project.service<MarsAssemblySettings>()
    val file = VirtualFileManager.getInstance().findFileByNioPath(sourceSet.entry)
    val psi = file?.let(PsiManager.getInstance(project)::findFile)
        ?: return collectBuildSources(sourceSet, settings.exceptionHandler)
    val key = captureKeys.getValue(sourceSet.mode)
    return CachedValuesManager.getCachedValue(psi, key) {
        val dependencies = arrayListOf<Any>(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, settings.sourceChanges)
        val currentSources = if (sourceSet.mode == MarsAssemblyMode.PROJECT) {
            MarsSourceSet(settings.projectSources, MarsAssemblyMode.PROJECT)
        } else sourceSet
        val captured = collectBuildSources(currentSources, settings.exceptionHandler) { dependencies.add(it) }
        CachedValueProvider.Result.create(captured, dependencies)
    }
}

internal suspend fun CapturedAssemblySources.assembleIn(component: MarsComponent): AssemblyResult {
    val context = currentCoroutineContext()
    return assembleIn(component, context::ensureActive)
}

internal fun CapturedAssemblySources.assembleIn(component: MarsComponent, checkCancellation: Runnable): AssemblyResult {
    val settings = component.simulationSettings()
    val result = component.assembler().assembleSnapshot(
        snapshot, exceptionHandler?.takeUnless { it in snapshot.entries() },
        settings.isExtendedAssemblerEnabled, settings.isWarningsAreErrors, checkCancellation,
    )
    if (!settings.isWarningsAreErrors) return result
    return AssemblyResult(result.program(), result.errors().map { diagnostic ->
        if (diagnostic.isWarning) {
            AssemblyError(diagnostic.file(), diagnostic.line(), diagnostic.column(), diagnostic.message(), false)
        } else diagnostic
    })
}

/** Validates source dependencies and supplies their current documents under the same read action. */
internal fun Map<Path, Long>.validateSourceDocuments(accept: (Path, Document, Long) -> Boolean): Boolean {
    val files = VirtualFileManager.getInstance()
    val documents = FileDocumentManager.getInstance()
    for ((path, revision) in this) {
        ProgressManager.checkCanceled()
        val source = files.findFileByNioPath(path) ?: return false
        val document = documents.getDocument(source) ?: return false
        if (!accept(path, document, revision)) return false
    }
    return true
}
