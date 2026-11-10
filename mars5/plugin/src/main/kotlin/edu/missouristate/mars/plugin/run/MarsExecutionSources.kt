package edu.missouristate.mars.plugin.run

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
    private class CapturedFile(val pathInImage: Path, text: String) :
        ReadOnlyLightVirtualFile(runMessages.getMessage("run.source.captured", pathInImage.fileName), MipsLanguage, text) {
        init { setFileType(MipsFileType) }
    }

    private val snapshots = ConcurrentHashMap<Path, CapturedFile>()
    private val texts = capture.snapshot.texts()

    fun contains(file: VirtualFile): Boolean = pathOf(file)?.let(texts::containsKey) == true

    /** Checks live source identity under a read action without loading uncached documents. */
    fun isCurrent(): Boolean = texts.all { (path, text) ->
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
        file != null && matches(file, path, text)
    }

    fun position(location: Location): XSourcePosition {
        val position = XDebuggerUtil.getInstance().createPosition(fileAt(location.file()), location.line() - 1)!!
        return object : XSourcePositionWrapper(position) {
            // A stack frame may have been created before the user edited the live document.
            override fun createNavigatable(project: Project) = descriptor(project, location)
        }
    }

    fun hyperlink(project: Project, location: Location): FileHyperlinkInfo = object : FileHyperlinkInfo {
        override fun getDescriptor() = descriptor(project, location)
        override fun navigate(project: Project) = getDescriptor().navigate(true)
    }

    private fun descriptor(project: Project, location: Location) =
        OpenFileDescriptor(project, fileAt(location.file()), location.line() - 1, 0)

    fun location(file: VirtualFile, line: Int): Location? {
        if (line !in 0 until Int.MAX_VALUE) return null
        val path = if (file is CapturedFile) {
            file.pathInImage.takeIf { snapshots[it] === file }
        } else {
            pathOf(file)?.takeIf { path ->
                texts[path]?.let { text -> matches(file, path, text) } == true
            }
        }
        return path?.let { Location(it, line + 1) }
    }

    private fun pathOf(file: VirtualFile) = file.fileSystem.getNioPath(file)?.let { it.toAbsolutePath().normalize() }

    private fun fileAt(path: Path): VirtualFile {
        val text = texts.getValue(path)
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
        if (file != null && matches(file, path, text)) return file
        return snapshots.computeIfAbsent(path) { CapturedFile(it, text) }
    }

    private fun matches(file: VirtualFile, path: Path, text: String): Boolean {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val capturedStamp = capture.revisions.getValue(path)
        if (document == null) return file.modificationStamp == capturedStamp
        return document.modificationStamp == capturedStamp || StringUtil.equals(document.immutableCharSequence, text)
    }
}
