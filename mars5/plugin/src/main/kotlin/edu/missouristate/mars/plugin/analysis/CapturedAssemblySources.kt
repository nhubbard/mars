package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFileManager
import edu.missouristate.mars.api.AssemblyResult
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.di.MarsComponent
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Map.copyOf as copyOfMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CapturedAssemblySources(
    val snapshot: AssemblySourceSnapshot,
    val exceptionHandler: Path?,
    val revisions: Map<Path, Long>,
)

/**
 * Capture one coherent document revision before handing off to the Java assembler.
 * The include prepass reads the captured text directly, including files with no MARS file type.
 */
internal suspend fun captureAssemblySources(entries: List<Path>, exceptionHandler: Path?): CapturedAssemblySources =
    readAction { collectAssemblySources(entries, exceptionHandler) }

/** Called with read access by Build or by IntelliJ's external highlighting pass. */
internal fun collectAssemblySources(entries: List<Path>, exceptionHandler: Path?): CapturedAssemblySources {
    val ordered = entries.map { it.toAbsolutePath().normalize() }
    val handler = exceptionHandler?.toAbsolutePath()?.normalize()
    val files = VirtualFileManager.getInstance()
    val documents = FileDocumentManager.getInstance()
    val pending = ArrayDeque(ordered + listOfNotNull(handler))
    val visited = HashSet<Path>()
    val texts = HashMap<Path, String>()
    val revisions = HashMap<Path, Long>()
    val tokenizer = Tokenizer()

    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val path = pending.removeFirst()
        if (!visited.add(path)) continue
        val document = files.findFileByNioPath(path)?.let(documents::getDocument) ?: continue
        val text = document.text
        texts[path] = text
        revisions[path] = document.modificationStamp

        for (line in text.lineSequence()) {
            ProgressManager.checkCanceled()
            val include = tokenizer.findIncludePath(line) ?: continue
            try {
                pending.addLast(path.resolveSibling(include).normalize())
            } catch (_: InvalidPathException) {
                // The assembler reports the malformed path at the captured include site.
            }
        }
    }
    return CapturedAssemblySources(AssemblySourceSnapshot(ordered, texts), handler, copyOfMap(revisions))
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
