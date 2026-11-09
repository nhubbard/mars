package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import edu.missouristate.mars.api.AssemblyResult
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService.AssemblySelection
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.references.findMipsIncludeFile
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Map.copyOf as copyOfMap
import java.util.Objects.requireNonNullElse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CapturedAssemblySources(
    val snapshot: AssemblySourceSnapshot,
    val exceptionHandler: Path?,
    val revisions: Map<Path, Long>,
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
        val selected = file.project.service<MarsAnalysisService>().selectedAssembly(file)
        if (selected != selectedAssembly) return false
        val entry = sources.snapshot.entries().single()
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
    val explicit = selected?.let { selection ->
        collectBackgroundAssemblySources(file.project, selection.entry).takeIf { source in it.revisions }
            ?.let { it to selection.options }
    }
    val (sources, options) = explicit ?: (
        collectBackgroundAssemblySources(file.project, inferredMipsAssemblyEntry(file) ?: return null) to MarsExecutionOptions()
    )
    return EditorAssemblyCapture(source, selected, sources, options, VirtualFileManager.getInstance().structureModificationCount)
}

private data class SourceDocument(val text: String, val revision: Long, val identity: Path)

private data class IncludeCursor(val path: Path, val lines: Iterator<String>)

/**
 * Capture one coherent document revision before handing off to the Java assembler.
 * The include prepass reads the captured text directly, including files with no MARS file type.
 */
internal suspend fun captureAssemblySources(entries: List<Path>, exceptionHandler: Path?): CapturedAssemblySources =
    readAction { collectAssemblySources(entries, exceptionHandler) }

/** Called with read access by Build or by IntelliJ's external highlighting pass. */
internal fun collectAssemblySources(
    entries: List<Path>, exceptionHandler: Path?, onDependency: (Any) -> Unit = {},
): CapturedAssemblySources {
    val ordered = entries.map { it.toAbsolutePath().normalize() }
    val handler = exceptionHandler?.toAbsolutePath()?.normalize()
    val files = VirtualFileManager.getInstance()
    val documents = FileDocumentManager.getInstance()
    val captured = HashMap<Path, SourceDocument>()
    val resolvedIncludes = HashMap<Path, Path>()
    val tokenizer = Tokenizer()

    fun capture(path: Path): SourceDocument? {
        captured[path]?.let { return it }
        val file = files.findFileByNioPath(path) ?: return null
        val document = documents.getDocument(file)
        onDependency(document ?: file)
        if (document == null) return null
        val parent: VirtualFile = file.parent
        // Directory aliases share an include location. A file symlink keeps its own name and directory.
        val identity = requireNonNullElse<VirtualFile>(parent.canonicalFile, parent).toNioPath().resolve(file.name)
        return SourceDocument(document.text, document.modificationStamp, identity).also { captured[path] = it }
    }

    for (root in ordered + listOfNotNull(handler)) {
        ProgressManager.checkCanceled()
        val seen = HashSet<Path>()
        val pending = ArrayDeque<IncludeCursor>()
        fun enter(path: Path) {
            val source = capture(path) ?: return
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
    return CapturedAssemblySources(snapshot, handler, copyOfMap(captured.mapValues { it.value.revision }))
}

/** Highlighting can reuse capture across editors until an input document or the VFS structure changes. */
internal fun collectBackgroundAssemblySources(project: Project, entry: Path): CapturedAssemblySources {
    val file = VirtualFileManager.getInstance().findFileByNioPath(entry)
    val psi = file?.let(PsiManager.getInstance(project)::findFile)
        ?: return collectAssemblySources(listOf(entry), null)
    return CachedValuesManager.getCachedValue(psi) {
        val dependencies = arrayListOf<Any>(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        val captured = collectAssemblySources(listOf(psi.virtualFile.toNioPath()), null) { dependencies.add(it) }
        CachedValueProvider.Result.create(captured, dependencies)
    }
}

internal suspend fun CapturedAssemblySources.assembleIn(component: MarsComponent): AssemblyResult {
    val context = currentCoroutineContext()
    return assembleIn(component, context::ensureActive)
}

internal fun CapturedAssemblySources.assembleIn(component: MarsComponent, checkCancellation: Runnable): AssemblyResult {
    val settings = component.simulationSettings()
    return component.assembler().assembleSnapshot(
        snapshot, exceptionHandler, settings.isExtendedAssemblerEnabled, settings.isWarningsAreErrors, checkCancellation,
    )
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
