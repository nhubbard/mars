package edu.missouristate.mars.plugin.language.psi

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import edu.missouristate.mars.plugin.language.index.forEachMipsInclude
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet

class MipsStringLiteral(type: IElementType, text: CharSequence) : LeafPsiElement(type, text) {
    override fun getReferences(): Array<PsiReference> {
        val path = mipsIncludeSites(containingFile).paths[textOffset] ?: return PsiReference.EMPTY_ARRAY
        if (path.isEmpty()) return PsiReference.EMPTY_ARRAY
        val references = MipsFileReferenceSet(path, this).allReferences
        return Array(references.size) { references[it] }
    }

    override fun getReference(): PsiReference? = references.firstOrNull()
}

/** Both maps are keyed by the opening quote. An unterminated entry stores the physical line start. */
internal data class MipsIncludeSites(val paths: Map<Int, String>, val unterminated: Map<Int, Int>)

internal fun mipsIncludeSites(file: PsiFile): MipsIncludeSites = CachedValuesManager.getCachedValue(file) {
    val paths = LinkedHashMap<Int, String>()
    val unterminated = LinkedHashMap<Int, Int>()
    forEachMipsInclude(file.node.chars) { start, include ->
        val quote = start + include.offset()
        val path = include.path()
        if (path == null) unterminated[quote] = start else paths[quote] = path
    }
    CachedValueProvider.Result.create(MipsIncludeSites(paths, unterminated), file)
}
