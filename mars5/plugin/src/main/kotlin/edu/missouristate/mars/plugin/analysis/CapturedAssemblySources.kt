package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFileManager
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.assembler.Tokenizer
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Map.copyOf as copyOfMap

internal data class CapturedAssemblySources(
    val snapshot: AssemblySourceSnapshot,
    val exceptionHandler: Path?,
    val revisions: Map<Path, Long>,
)

/**
 * Capture one coherent document revision before handing off to the Java assembler.
 * The include prepass reads the captured text directly, including files with no MARS file type.
 */
internal suspend fun captureAssemblySources(entries: List<Path>, exceptionHandler: Path?): CapturedAssemblySources {
    val ordered = entries.map { it.toAbsolutePath().normalize() }
    val handler = exceptionHandler?.toAbsolutePath()?.normalize()
    return readAction {
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
        CapturedAssemblySources(AssemblySourceSnapshot(ordered, texts), handler, copyOfMap(revisions))
    }
}
