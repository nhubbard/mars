package edu.missouristate.mars.plugin.language.index

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.platform.syntax.element.SyntaxTokenTypes.WHITE_SPACE
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.impl.include.FileIncludeInfo
import com.intellij.psi.impl.include.FileIncludeProvider
import com.intellij.util.Consumer
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.IndexedFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsLexer
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.COMMA
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.INCLUDE
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.NEWLINE
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.STRING
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet

/** Supplies unresolved paths to IntelliJ's shared include index, matching MARS's include prepass. */
class MipsIncludeProvider : FileIncludeProvider() {
    override fun getId(): String = "mars"
    override fun getVersion(): Int = 3
    override fun acceptFile(indexedFile: IndexedFile): Boolean = indexedFile.fileType == MipsFileType
    override fun registerFileTypesUsedForIndexing(fileTypeSink: Consumer<in FileType>) = fileTypeSink.consume(MipsFileType)

    override fun getIncludeInfos(content: FileContent): Array<FileIncludeInfo> = collectMipsIncludes(content.contentAsText)

    override fun resolveIncludedFile(info: FileIncludeInfo, context: PsiFile): PsiFileSystemItem? {
        if (info.offset < 0) return null
        val literal = context.findElementAt(info.offset) as? MipsStringLiteral ?: return null
        return MipsFileReferenceSet(info.path, literal).resolve()
    }
}

internal fun collectMipsIncludes(text: CharSequence): Array<FileIncludeInfo> {
    val lexer = MipsLexer()
    lexer.start(text, 0, text.length, 0)
    val includes = ArrayList<FileIncludeInfo>()
    var expectingPath = false
    var includedOnLine = false
    while (true) {
        ProgressManager.checkCanceled()
        val type = lexer.getTokenType() ?: break
        when (type) {
            NEWLINE -> {
                expectingPath = false
                includedOnLine = false
            }
            WHITE_SPACE, COMMA -> Unit
            else -> if (!includedOnLine) {
                if (expectingPath && type == STRING) {
                    val quoted = text.subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString()
                    // The assembler strips quotes here without decoding string escapes.
                    includes.add(FileIncludeInfo(quoted.substring(1, quoted.length - 1), lexer.getTokenStart()))
                    includedOnLine = true
                }
                expectingPath = type == INCLUDE
            }
        }
        lexer.advance()
    }
    return includes.toTypedArray()
}
