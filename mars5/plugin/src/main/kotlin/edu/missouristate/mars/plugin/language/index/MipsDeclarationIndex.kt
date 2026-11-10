package edu.missouristate.mars.plugin.language.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.io.DataInput
import java.io.DataOutput

/** A syntactic candidate. The macro offset describes physical PSI nesting before source expansion. */
data class MipsDeclarationSite(val kind: SymbolKind, val offset: Int, val macroOffset: Int)

/** Index only this file's syntax. Never resolve names or query another index while producing facts. */
class MipsDeclarationIndex : FileBasedIndexExtension<String, List<MipsDeclarationSite>>() {
    override fun getName() = NAME
    override fun getVersion(): Int = 25
    override fun dependsOnFileContent(): Boolean = true
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(MipsFileType)
    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer(): DataExternalizer<List<MipsDeclarationSite>> = MipsDeclarationExternalizer

    override fun getIndexer() = DataIndexer<String, List<MipsDeclarationSite>, FileContent> { content ->
        val declarations = LinkedHashMap<String, MutableList<MipsDeclarationSite>>()
        content.psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element.node.elementType in expansionSeeds) declarations.getOrPut(EXPANSION_SEED, ::ArrayList)
                if (element is MipsDeclaration) {
                    val name = element.name
                    if (name != null) {
                        val scopeStart = if (element.kind == SymbolKind.MACRO) element.parent else element
                        val macro = PsiTreeUtil.findFirstParent(scopeStart, true) {
                            PsiUtilCore.getElementType(it) == MipsTypes.psi("MACRO_DEFINITION")
                        }
                        declarations.getOrPut(name, ::ArrayList).add(
                            MipsDeclarationSite(element.kind, element.textOffset, macro?.textOffset ?: -1),
                        )
                    }
                }
                super.visitElement(element)
            }
        })
        declarations
    }

    companion object {
        val NAME: ID<String, List<MipsDeclarationSite>> = ID.create("mars.declarations")
        internal const val EXPANSION_SEED = "#mars.expansion"
        private val expansionSeeds = TokenSet.create(MipsTypes.psi("EQV"), MipsTypes.psi("INCLUDE_LINE"))
    }
}

internal object MipsDeclarationExternalizer : DataExternalizer<List<MipsDeclarationSite>> {
    override fun save(out: DataOutput, value: List<MipsDeclarationSite>) {
        out.writeInt(value.size)
        for (site in value) {
            out.writeByte(site.kind.ordinal)
            out.writeInt(site.offset)
            out.writeInt(site.macroOffset)
        }
    }

    override fun read(input: DataInput): List<MipsDeclarationSite> = List(input.readInt()) {
        MipsDeclarationSite(SymbolKind.entries[input.readUnsignedByte()], input.readInt(), input.readInt())
    }
}
