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
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet
import edu.missouristate.mars.plugin.language.syntax.inspectMipsInclude

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
    forEachMipsInclude(text) { start, include ->
        val path = include.path()
        if (path != null) includes.add(FileIncludeInfo(path, start + include.offset()))
    }
    return includes.toTypedArray()
}

internal fun forEachMipsInclude(text: CharSequence, visit: (Int, Tokenizer.IncludePath) -> Unit) {
    val lines = LineTokenizer(text)
    while (!lines.atEnd()) {
        ProgressManager.checkCanceled()
        val start = lines.offset
        val include = inspectMipsInclude(text, start, start + lines.length)
        if (include != null) visit(start, include)
        lines.advance()
    }
}
