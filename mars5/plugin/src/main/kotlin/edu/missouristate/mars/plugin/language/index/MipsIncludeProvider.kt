package edu.missouristate.mars.plugin.language.index

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfoRt
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
class MipsIncludeProvider internal constructor(private val windowsPaths: Boolean) : FileIncludeProvider() {
    constructor() : this(SystemInfoRt.isWindows)

    override fun getId(): String = "mars"
    override fun getVersion(): Int = 9
    override fun acceptFile(indexedFile: IndexedFile): Boolean = indexedFile.fileType == MipsFileType
    override fun registerFileTypesUsedForIndexing(fileTypeSink: Consumer<in FileType>) = fileTypeSink.consume(MipsFileType)

    override fun getIncludeInfos(content: FileContent): Array<FileIncludeInfo> {
        val includes = ArrayList<FileIncludeInfo>()
        forEachMipsInclude(content.contentAsText) { start, include ->
            val path = include.path() ?: return@forEachMipsInclude
            val normalized = if (windowsPaths) path.replace('\\', '/') else path
            val name = includeKey(normalized.substringAfterLast('/'))
            includes.add(FileIncludeInfo(name, path, start + include.offset(), false))
        }
        return includes.toTypedArray()
    }

    override fun getIncludeName(file: PsiFile, originalName: String): String = includeKey(originalName)

    private fun includeKey(name: String): String = if (windowsPaths) name.lowercase() else name

    override fun resolveIncludedFile(info: FileIncludeInfo, context: PsiFile): PsiFileSystemItem? =
        resolveIndexedMipsInclude(info, context)
}

internal fun resolveIndexedMipsInclude(info: FileIncludeInfo, context: PsiFile): PsiFileSystemItem? {
    if (info.offset < 0) return null
    val literal = context.findElementAt(info.offset) as? MipsStringLiteral ?: return null
    return MipsFileReferenceSet(info.path, literal).resolve()
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
