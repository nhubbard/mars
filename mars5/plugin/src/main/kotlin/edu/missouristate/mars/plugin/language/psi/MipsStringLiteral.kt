package edu.missouristate.mars.plugin.language.psi

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import edu.missouristate.mars.plugin.language.index.collectMipsIncludes
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet

class MipsStringLiteral(type: IElementType, text: CharSequence) : LeafPsiElement(type, text) {
    override fun getReferences(): Array<PsiReference> {
        val path = mipsIncludePaths(containingFile)[textOffset] ?: return PsiReference.EMPTY_ARRAY
        if (path.isEmpty()) return PsiReference.EMPTY_ARRAY
        val references = MipsFileReferenceSet(path, this).allReferences
        return Array(references.size) { references[it] }
    }

    override fun getReference(): PsiReference? = references.firstOrNull()
}

internal fun mipsIncludePaths(file: PsiFile): Map<Int, String> = CachedValuesManager.getCachedValue(file) {
    CachedValueProvider.Result.create(collectMipsIncludes(file.node.chars).associate { it.offset to it.path }, file)
}
