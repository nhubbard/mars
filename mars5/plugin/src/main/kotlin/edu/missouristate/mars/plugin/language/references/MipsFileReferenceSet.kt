package edu.missouristate.mars.plugin.language.references

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceHelper
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.plugin.language.mipsAssemblySourceOrigin
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral

/** MARS paths are literal filesystem paths. Spaces and question marks are not URL syntax. */
class MipsFileReferenceSet internal constructor(
    path: String,
    literal: MipsStringLiteral,
    private val windowsPaths: Boolean = SystemInfoRt.isWindows,
) : FileReferenceSet(path, literal, 1, null, true, true, null, false) {
    init {
        reparse()
    }

    override fun getPathString(): String = element.text.substring(1, element.textLength - 1)
    override fun isAbsolutePathReference(): Boolean = absoluteRoot(referencePath(pathString)).isNotEmpty()

    override fun computeDefaultContexts(): Collection<PsiFileSystemItem> {
        val root = absoluteRoot(referencePath(pathString))
        if (root.isEmpty()) return parentDirectoryContext
        val directory = LocalFileSystem.getInstance().findFileByPath(root)?.let(element.manager::findDirectory)
        return listOfNotNull(directory)
    }

    override fun getParentDirectoryContext(): Collection<PsiFileSystemItem> {
        val source = element.containingFile.viewProvider.virtualFile.mipsAssemblySourceOrigin
            ?: return super.getParentDirectoryContext()
        // Only physical include files have a backed MIPS source view.
        return listOfNotNull(element.manager.findDirectory(source.parent!!))
    }

    override fun getNewAbsolutePath(root: PsiFileSystemItem, relativePath: String): String =
        root.virtualFile.path.trimEnd('/') + "/" + relativePath

    override fun reparse(str: String, startInElement: Int): List<FileReference> {
        val references = ArrayList<FileReference>()
        val path = referencePath(str)
        val root = absoluteRoot(path)
        var start = root.length
        while (start < str.length) {
            val separator = path.indexOf('/', start)
            val end = if (separator < 0) str.length else separator
            if (end > start) {
                references.add(createFileReference(
                    TextRange(startInElement + start, startInElement + end), references.size, str.substring(start, end),
                ))
            }
            start = end + 1
        }
        if (references.isEmpty() && root.isNotEmpty()) {
            references.add(createFileReference(TextRange.from(startInElement, str.length), 0, "/"))
        }
        return references
    }

    // Each replacement has the same length, so references still point into the written literal.
    private fun referencePath(path: String): String = if (windowsPaths) path.replace('\\', '/') else path

    private fun absoluteRoot(path: String): String {
        if (!windowsPaths) return if (path.startsWith('/')) "/" else ""
        if (OSAgnosticPathUtil.isAbsoluteDosPath(path)) return path.substring(0, 3)
        if (!path.startsWith("//")) return ""
        // The server and share belong to the filesystem root rather than to individual references.
        val serverEnd = path.indexOf('/', 2)
        if (serverEnd < 0) return path
        val shareStart = CharArrayUtil.shiftForward(path, serverEnd, "/")
        val shareEnd = path.indexOf('/', shareStart)
        return if (shareEnd < 0) path else path.substring(0, shareEnd)
    }

    override fun createFileReference(range: TextRange, index: Int, text: String): FileReference =
        object : FileReference(this, range, index, text) {
            override fun decode(text: String): String = text

            override fun innerResolveInContext(
                text: String, context: PsiFileSystemItem, result: MutableCollection<in ResolveResult>,
                caseSensitive: Boolean,
            ) {
                if (text in dotSegments) {
                    val directory = context.virtualFile.resolveMipsDotSegment(text)?.let(context.manager::findDirectory)
                    if (directory != null) result.add(PsiElementResolveResult(directory))
                } else {
                    super.innerResolveInContext(text, context, result, context.virtualFile.isCaseSensitive)
                }
            }

            override fun getContextsForBindToElement(
                curVFile: VirtualFile, project: Project, helper: FileReferenceHelper,
            ): Collection<PsiFileSystemItem> = this@MipsFileReferenceSet.parentDirectoryContext
        }
}
