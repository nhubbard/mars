package edu.missouristate.mars.plugin.language.references

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceHelper
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral

/** MARS paths are literal filesystem paths. Spaces and question marks are not URL syntax. */
class MipsFileReferenceSet(path: String, literal: MipsStringLiteral) : FileReferenceSet(path, literal, 1, null, true, true) {
    override fun getPathString(): String = element.text.substring(1, element.textLength - 1)
    override fun isAbsolutePathReference(): Boolean = pathString.startsWith('/')

    override fun computeDefaultContexts(): Collection<PsiFileSystemItem> {
        if (!isAbsolutePathReference) return parentDirectoryContext
        val file = element.containingFile.originalFile.viewProvider.virtualFile
        return listOfNotNull(element.manager.findDirectory(VfsUtilCore.getRootFile(file)))
    }

    override fun getNewAbsolutePath(root: PsiFileSystemItem, relativePath: String): String =
        root.virtualFile.path.trimEnd('/') + "/" + relativePath

    override fun reparse(str: String, startInElement: Int): List<FileReference> {
        val references = ArrayList<FileReference>()
        var start = 0
        while (start < str.length) {
            val separator = str.indexOf('/', start)
            val end = if (separator < 0) str.length else separator
            if (end > start) {
                references.add(createFileReference(
                    TextRange(startInElement + start, startInElement + end), references.size, str.substring(start, end),
                ))
            }
            start = end + 1
        }
        if (references.isEmpty() && str.startsWith('/')) {
            references.add(createFileReference(TextRange.from(startInElement, str.length), 0, "/"))
        }
        return references
    }

    override fun createFileReference(range: TextRange, index: Int, text: String): FileReference =
        object : FileReference(this, range, index, text) {
            override fun decode(text: String): String = text
            override fun getContextsForBindToElement(
                curVFile: VirtualFile, project: Project, helper: FileReferenceHelper,
            ): Collection<PsiFileSystemItem> = this@MipsFileReferenceSet.parentDirectoryContext
        }
}
