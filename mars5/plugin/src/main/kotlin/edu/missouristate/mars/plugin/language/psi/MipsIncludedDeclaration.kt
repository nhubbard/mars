package edu.missouristate.mars.plugin.language.psi

import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.impl.light.LightElement
import edu.missouristate.mars.plugin.language.MipsLanguage
import edu.missouristate.mars.plugin.language.syntax.MipsScanner

/** A declaration range in an assembler include whose file type has no MARS PSI. */
internal data class MipsIncludedDeclaration(
    private val file: PsiFile,
    private val range: TextRange,
    private val spelling: String,
    private val revision: Long,
) : LightElement(file.manager, MipsLanguage) {
    override fun getContainingFile() = file
    override fun getTextRange() = range
    override fun getTextOffset() = range.startOffset
    override fun getText() = spelling
    override fun isValid() = file.isValid && file.fileDocument.modificationStamp == revision
    override fun getPresentation() = PresentationData(spelling, file.name, null, null)

    companion object {
        fun at(file: PsiFile, offset: Int): MipsIncludedDeclaration {
            val source = file.viewProvider.contents
            val end = MipsScanner.next(source, offset)!!.end
            val range = TextRange(offset, end)
            return MipsIncludedDeclaration(file, range, range.subSequence(source).toString(), file.fileDocument.modificationStamp)
        }
    }
}

internal fun PsiElement.writtenDeclarationAt(offset: Int = textOffset): PsiElement {
    val original = containingFile.originalFile
    return if (original is PsiPlainTextFile) MipsIncludedDeclaration.at(original, offset) else this
}
