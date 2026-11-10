package edu.missouristate.mars.plugin.language.index

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.text.LineTokenizer
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.impl.include.FileIncludeInfo
import com.intellij.psi.impl.include.FileIncludeProvider
import com.intellij.util.Consumer
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.IndexedFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet
import edu.missouristate.mars.plugin.language.syntax.findMipsInclude

/** Supplies unresolved paths to IntelliJ's shared include index, matching MARS's include prepass. */
class MipsIncludeProvider : FileIncludeProvider() {
    override fun getId(): String = "mars"
    override fun getVersion(): Int = 8
    override fun acceptFile(indexedFile: IndexedFile): Boolean = indexedFile.fileType == MipsFileType
    override fun registerFileTypesUsedForIndexing(fileTypeSink: Consumer<in FileType>) = fileTypeSink.consume(MipsFileType)

    override fun getIncludeInfos(content: FileContent): Array<FileIncludeInfo> = collectMipsIncludes(content.contentAsText)

    override fun resolveIncludedFile(info: FileIncludeInfo, context: PsiFile): PsiFileSystemItem? =
        resolveIndexedMipsInclude(info, context)
}

internal fun resolveIndexedMipsInclude(info: FileIncludeInfo, context: PsiFile): PsiFileSystemItem? {
    if (info.offset < 0) return null
    val literal = context.findElementAt(info.offset) as? MipsStringLiteral ?: return null
    return MipsFileReferenceSet(info.path, literal).resolve()
}

internal fun collectMipsIncludes(text: CharSequence): Array<FileIncludeInfo> {
    val includes = ArrayList<FileIncludeInfo>()
    val lines = LineTokenizer(text)
    while (!lines.atEnd()) {
        ProgressManager.checkCanceled()
        val start = lines.offset
        findMipsInclude(text, start, start + lines.length)?.let {
            includes.add(FileIncludeInfo(it.path(), start + it.offset()))
        }
        lines.advance()
    }
    return includes.toTypedArray()
}
