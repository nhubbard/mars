package edu.missouristate.mars.plugin.run

import com.intellij.diff.tools.util.text.LineOffsets
import com.intellij.diff.tools.util.text.LineOffsetsUtil
import com.intellij.execution.filters.FileHyperlinkInfo
import com.intellij.notebook.editor.BackedVirtualFile
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ReadOnlyLightVirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.text.CharArrayUtil
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.XSourcePositionWrapper
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.analysis.assemblySourceSyntax
import edu.missouristate.mars.plugin.analysis.unresolvedAssemblySourceSyntax
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsLanguage
import edu.missouristate.mars.plugin.language.mipsAssemblySourceOrigin
import edu.missouristate.mars.plugin.language.rememberMipsAssemblyEntry
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Resolves debugger positions and console links against the sources of one loaded image. */
internal class MarsExecutionSources(private val capture: CapturedAssemblySources) {
    private open class CapturedFile(
        val pathInImage: Path, val firstLine: Int, val owner: Any, name: String, text: String,
    ) : ReadOnlyLightVirtualFile(name, MipsLanguage, text) {
        init { fileType = MipsFileType }
    }

    private class BackedExcerpt(
        private val original: VirtualFile, path: Path, firstLine: Int, owner: Any, name: String, text: String,
    ) : CapturedFile(path, firstLine, owner, name, text), BackedVirtualFile {
        override fun getOriginFile() = original
    }

    private data class ExcerptStart(
        val path: Path, val offset: Int, val firstLine: Int, val prefix: String, val original: VirtualFile? = null,
    )

    private val snapshotOwner = Any()
    private val snapshots = ConcurrentHashMap<Path, CapturedFile>()
    private val excerpts = ConcurrentHashMap<ExcerptStart, CapturedFile>()
    private val lineOffsets = ConcurrentHashMap<Path, LineOffsets>()
    private val texts = capture.snapshot.texts()

    fun contains(file: VirtualFile): Boolean = pathOf(file)?.let(texts::containsKey) == true

    /** Checks live source identity under a read action without loading uncached documents. */
    fun isCurrent(): Boolean = texts.all { (path, text) ->
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
        file != null && matchesBreakpointSource(file, path, text)
    }

    /** Navigates an assembly origin only while its source still matches the captured revision. */
    fun currentLineDescriptor(project: Project, location: Location): OpenFileDescriptor? {
        val path = location.file()
        val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
        val line = location.line() - 1
        if (path in capture.diskSources) {
            if (!file.fileType.isBinary || file.modificationStamp != capture.revisions.getValue(path)) return null
            return currentReadOnlyLine(project, file, path, texts.getValue(path), line)
        }
        val documents = FileDocumentManager.getInstance()
        val document = if (file.fileType.isBinary) null else documents.getDocument(file)
        if (document == null) return null
        val text = texts[path] ?: return null
        val partial = documents.isPartialPreviewOfALargeFile(document)
        val current = if (partial) document.modificationStamp == capture.revisions.getValue(path)
            else StringUtil.equals(document.immutableCharSequence, text)
        if (!current) return null

        if (line < document.lineCount) {
            if (FileTypeRegistry.getInstance().isFileIgnored(file)) return currentReadOnlyLine(project, file, path, text, line)
            return OpenFileDescriptor(project, file, document.getLineStartOffset(line))
        }
        if (!partial || line >= offsets(path, text).lineCount) return null
        val excerpt = excerptAt(path, text, line)
        return OpenFileDescriptor(project, excerpt, line - excerpt.firstLine, 0)
    }

    private fun currentReadOnlyLine(project: Project, file: VirtualFile, path: Path, text: String, line: Int): OpenFileDescriptor? {
        if (line >= offsets(path, text).lineCount) return null
        val view = readOnlyView(project, file) ?: excerptAt(path, text, line)
        return OpenFileDescriptor(project, view, line - firstLine(view), 0)
    }

    private fun readOnlyView(project: Project, file: VirtualFile): VirtualFile? {
        val physical = PsiManager.getInstance(project).findFile(file)
        val syntax = if (physical == null) unresolvedAssemblySourceSyntax(project, file) else assemblySourceSyntax(physical)
        return syntax?.virtualFile
    }

    fun rememberEntryFor(file: VirtualFile, location: Location) {
        if (file.mipsAssemblySourceOrigin != null) {
            val root = capture.rootsBySource.getValue(location.file())
            // The editor rechecks this candidate against the current graph before using it.
            LocalFileSystem.getInstance().findFileByNioFile(root)?.let(file::rememberMipsAssemblyEntry)
        }
    }

    fun navigateTo(descriptor: OpenFileDescriptor, location: Location) {
        rememberEntryFor(descriptor.file, location)
        descriptor.navigate(true)
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
        override fun getDescriptor() = runReadActionBlocking {
            descriptor(project, location, column, fileAt(location, project))
        }
        override fun navigate(project: Project) = navigateTo(getDescriptor(), location)
    }

    private fun descriptor(
        project: Project, location: Location, column: Int? = null, file: VirtualFile = fileAt(location),
    ): OpenFileDescriptor {
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
            val original = file.mipsAssemblySourceOrigin
            val source = original ?: file
            pathOf(source)?.takeIf { path ->
                val text = texts[path] ?: return@takeIf false
                matchesBreakpointSource(source, path, text) &&
                    (original == null || StringUtil.equals((file as LightVirtualFile).content, text))
            }
        }
        return path?.let { Location(it, firstLine + line + 1) }
    }

    fun originalLine(file: VirtualFile, line: Int): Pair<VirtualFile, Location>? {
        if (file !is CapturedFile) return null
        val location = location(file, line) ?: return null
        val original = LocalFileSystem.getInstance().findFileByNioFile(location.file()) ?: return null
        return (original to location).takeIf { matchesBreakpointSource(original, location.file(), texts.getValue(location.file())) }
    }

    private fun firstLine(file: VirtualFile): Int = (file as? CapturedFile)?.firstLine ?: 0

    private fun pathOf(file: VirtualFile) = file.fileSystem.getNioPath(file)?.toAbsolutePath()?.normalize()

    private fun fileAt(location: Location, consoleProject: Project? = null): VirtualFile {
        val path = location.file()
        val text = texts.getValue(path)
        if (location.line() > capture.previewLineCounts.getOrDefault(path, Int.MAX_VALUE)) {
            return excerptAt(path, text, location.line() - 1)
        }
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
        if (file != null) {
            if (matches(file, path, text) && !FileTypeRegistry.getInstance().isFileIgnored(file)) return file
            // Debugger positions keep their execution snapshot. Console links can open the current editor view.
            if (consoleProject != null && matchesBreakpointSource(file, path, text)) {
                return readOnlyView(consoleProject, file) ?: excerptAt(path, text, location.line() - 1)
            }
        }
        return snapshots.computeIfAbsent(path) {
            CapturedFile(it, 0, snapshotOwner, runMessages.getMessage("run.source.captured", it.fileName), text)
        }
    }

    /** Returns the declaration's offset inside bounded, read-only source text. */
    fun excerptPosition(location: Location, column: Int?, original: VirtualFile? = null): Pair<VirtualFile, Int>? {
        val path = location.file()
        val text = texts.getValue(path)
        val line = location.line() - 1
        val offsets = offsets(path, text)
        val lineStart = offsets.getLineStart(line)
        val token = column?.let { lineStart + it - 1 } ?: CharArrayUtil.shiftForward(text, lineStart, " \t,")
        if (token >= offsets.getLineEnd(line)) return null

        val start = excerptStart(path, offsets, line).copy(original = original)
        if (token - start.offset >= EXCERPT_SIZE) {
            // Begin at the declaration when a long line would use up its surrounding context.
            return excerpt(ExcerptStart(path, token, line, "", original), text) to 0
        }
        return excerpt(start, text) to
            (token - start.offset + start.prefix.length)
    }

    private fun offsets(path: Path, text: String): LineOffsets =
        lineOffsets.computeIfAbsent(path) { LineOffsetsUtil.create(text) }

    private fun excerptAt(path: Path, text: String, line: Int): CapturedFile =
        excerpt(excerptStart(path, offsets(path, text), line), text)

    private fun excerptStart(path: Path, offsets: LineOffsets, line: Int): ExcerptStart {
        val pageStart = offsets.getLineStart(line) / EXCERPT_SIZE * EXCERPT_SIZE
        val first = offsets.getLineNumber(pageStart)
        // The initial partial line is context only. Give it comment syntax.
        val prefix = if (pageStart == offsets.getLineStart(first)) "" else "# "
        return ExcerptStart(path, pageStart, first, prefix)
    }

    private fun excerpt(start: ExcerptStart, text: String): CapturedFile =
        excerpts.computeIfAbsent(start) {
            val end = it.offset + minOf(text.length - it.offset, 2 * EXCERPT_SIZE)
            val name = runMessages.getMessage("run.source.excerpt", it.path.fileName, it.firstLine + 1)
            val content = it.prefix + text.substring(it.offset, end)
            val file = if (it.original == null) CapturedFile(it.path, it.firstLine, snapshotOwner, name, content)
                else BackedExcerpt(it.original, it.path, it.firstLine, snapshotOwner, name, content)
            file.apply { putUserData(excerptKey, true) }
        }

    private fun matches(file: VirtualFile, path: Path, text: String): Boolean {
        if (file.fileType.isBinary || path in capture.diskSources) return false
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val capturedStamp = capture.revisions.getValue(path)
        if (document == null) return file.modificationStamp == capturedStamp
        return document.modificationStamp == capturedStamp || StringUtil.equals(document.immutableCharSequence, text)
    }

    private fun matchesBreakpointSource(file: VirtualFile, path: Path, text: String): Boolean =
        if (path in capture.diskSources) file.fileType.isBinary && file.modificationStamp == capture.revisions.getValue(path)
        else matches(file, path, text)

    companion object {
        private const val EXCERPT_SIZE = 32 * 1024
        private val excerptKey = Key.create<Boolean>("mars.assembly.excerpt")

        fun isExcerpt(file: VirtualFile): Boolean = file.getUserData(excerptKey) == true
    }
}
