package edu.missouristate.mars.plugin.run

import com.intellij.diff.tools.util.text.LineOffsets
import com.intellij.diff.tools.util.text.LineOffsetsUtil
import com.intellij.execution.filters.FileHyperlinkInfo
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ReadOnlyLightVirtualFile
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.XSourcePositionWrapper
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsLanguage
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Resolves debugger positions and console links against the sources of one loaded image. */
internal class MarsExecutionSources(private val capture: CapturedAssemblySources) {
    private class CapturedFile(
        val pathInImage: Path, val firstLine: Int, val owner: Any, name: String, text: String,
    ) : ReadOnlyLightVirtualFile(name, MipsLanguage, text) {
        init { setFileType(MipsFileType) }
    }

    private val snapshotOwner = Any()
    private val snapshots = ConcurrentHashMap<Path, CapturedFile>()
    private val excerpts = ConcurrentHashMap<Pair<Path, Int>, CapturedFile>()
    private val lineOffsets = ConcurrentHashMap<Path, LineOffsets>()
    private val texts = capture.snapshot.texts()

    fun contains(file: VirtualFile): Boolean = pathOf(file)?.let(texts::containsKey) == true

    /** Checks live source identity under a read action without loading uncached documents. */
    fun isCurrent(): Boolean = texts.all { (path, text) ->
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
        file != null && matches(file, path, text)
    }

    fun position(location: Location): XSourcePosition {
        val file = fileAt(location)
        val position = XDebuggerUtil.getInstance().createPosition(file, location.line() - 1 - firstLine(file))!!
        return object : XSourcePositionWrapper(position) {
            // A stack frame may have been created before the user edited the live document.
            override fun createNavigatable(project: Project) = descriptor(project, location)
        }
    }

    fun hyperlink(project: Project, location: Location, column: Int? = null): FileHyperlinkInfo = object : FileHyperlinkInfo {
        override fun getDescriptor() = descriptor(project, location, column)
        override fun navigate(project: Project) = getDescriptor().navigate(true)
    }

    private fun descriptor(project: Project, location: Location, column: Int? = null): OpenFileDescriptor {
        val file = fileAt(location)
        val line = location.line() - 1 - firstLine(file)
        if (column == null) return OpenFileDescriptor(project, file, line, 0)
        // Assembler columns count source characters. IntelliJ logical columns expand tabs.
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        // A preview may not contain the line, but the descriptor can still carry its number.
        if (line >= document.lineCount) return OpenFileDescriptor(project, file, line, 0)
        return OpenFileDescriptor(project, file, document.getLineStartOffset(line) + column - 1)
    }

    fun location(file: VirtualFile, line: Int): Location? {
        val firstLine = firstLine(file)
        if (line !in 0 until Int.MAX_VALUE - firstLine) return null
        val path = if (file is CapturedFile) {
            file.pathInImage.takeIf { file.owner === snapshotOwner }
        } else {
            pathOf(file)?.takeIf { path ->
                texts[path]?.let { text -> matches(file, path, text) } == true
            }
        }
        return path?.let { Location(it, firstLine + line + 1) }
    }

    fun originalLine(file: VirtualFile, line: Int): Pair<VirtualFile, Location>? {
        if (file !is CapturedFile) return null
        val location = location(file, line) ?: return null
        val original = LocalFileSystem.getInstance().findFileByNioFile(location.file()) ?: return null
        return (original to location).takeIf { matches(original, location.file(), texts.getValue(location.file())) }
    }

    private fun firstLine(file: VirtualFile): Int = (file as? CapturedFile)?.firstLine ?: 0

    private fun pathOf(file: VirtualFile) = file.fileSystem.getNioPath(file)?.let { it.toAbsolutePath().normalize() }

    private fun fileAt(location: Location): VirtualFile {
        val path = location.file()
        val text = texts.getValue(path)
        if (location.line() > capture.previewLineCounts.getOrDefault(path, Int.MAX_VALUE)) {
            return excerptAt(path, text, location.line() - 1)
        }
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
        if (file != null && matches(file, path, text)) return file
        return snapshots.computeIfAbsent(path) {
            CapturedFile(it, 0, snapshotOwner, runMessages.getMessage("run.source.captured", it.fileName), text)
        }
    }

    private fun excerptAt(path: Path, text: String, line: Int): CapturedFile {
        val offsets = lineOffsets.computeIfAbsent(path) { LineOffsetsUtil.create(text) }
        val pageStart = offsets.getLineStart(line) / EXCERPT_SIZE * EXCERPT_SIZE
        return excerpts.computeIfAbsent(path to pageStart) {
            val first = offsets.getLineNumber(pageStart)
            // The initial partial line is context only. Give it comment syntax.
            val prefix = if (pageStart == offsets.getLineStart(first)) "" else "# "
            val end = pageStart + minOf(text.length - pageStart, 2 * EXCERPT_SIZE)
            CapturedFile(
                path, first, snapshotOwner, runMessages.getMessage("run.source.excerpt", path.fileName, first + 1),
                prefix + text.substring(pageStart, end),
            )
        }
    }

    private fun matches(file: VirtualFile, path: Path, text: String): Boolean {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val capturedStamp = capture.revisions.getValue(path)
        if (document == null) return file.modificationStamp == capturedStamp
        return document.modificationStamp == capturedStamp || StringUtil.equals(document.immutableCharSequence, text)
    }

    private companion object {
        const val EXCERPT_SIZE = 32 * 1024
    }
}
