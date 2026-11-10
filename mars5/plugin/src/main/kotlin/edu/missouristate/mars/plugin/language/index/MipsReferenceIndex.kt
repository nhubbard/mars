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
import java.io.DataInput
import java.io.DataOutput

/** A possible reference target kind at a physical token offset before source expansion. */
data class MipsReferenceSite(val kind: SymbolKind, val offset: Int, val macroOffset: Int)

class MipsReferenceIndex : FileBasedIndexExtension<String, List<MipsReferenceSite>>() {
    override fun getName() = NAME
    override fun getVersion(): Int = 19
    override fun dependsOnFileContent(): Boolean = true
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(MipsFileType)
    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer(): DataExternalizer<List<MipsReferenceSite>> = MipsReferenceExternalizer

    override fun getIndexer() = DataIndexer<String, List<MipsReferenceSite>, FileContent> { content ->
        val references = LinkedHashMap<String, MutableList<MipsReferenceSite>>()
        content.psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element.node.elementType in SYMBOL_TOKENS) {
                    val kinds = targetKinds(element)
                    if (kinds.isNotEmpty()) {
                        val macro = PsiTreeUtil.findFirstParent(element, true) {
                            PsiUtilCore.getElementType(it) == MipsTypes.psi("MACRO_DEFINITION")
                        }
                        val sites = references.getOrPut(element.text, ::ArrayList)
                        for (kind in kinds) sites.add(MipsReferenceSite(kind, element.textOffset, macro?.textOffset ?: -1))
                    }
                }
                super.visitElement(element)
            }
        })
        references
    }

    companion object {
        val NAME: ID<String, List<MipsReferenceSite>> = ID.create("mars.references")

        private val SYMBOL_TOKENS = TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("MNEMONIC"))
        private val OPERAND_CONTEXTS = TokenSet.create(MipsTypes.psi("REFERENCE_EXPR"), MipsTypes.psi("OPERAND_FRAGMENT"))
        private val EXPANSION_CONTEXTS = TokenSet.create(
            MipsTypes.psi("OPERATION_CANDIDATE"), MipsTypes.psi("INVOCATION_TOKEN"), MipsTypes.psi("REPLACEMENT"),
        )
        private val LABEL_KINDS = listOf(SymbolKind.LABEL, SymbolKind.EXTERNAL)
        private val CONSTANT_KINDS = listOf(SymbolKind.CONSTANT)
        private val OPERAND_KINDS = LABEL_KINDS + SymbolKind.CONSTANT

        private fun targetKinds(element: PsiElement): List<SymbolKind> {
            val context = PsiUtilCore.getElementType(element.parent)
            return when (context) {
                MipsTypes.psi("SEGMENT_ADDRESS_SYMBOL") -> CONSTANT_KINDS
                MipsTypes.psi("LABEL"), MipsTypes.psi("MACRO_SIGNATURE"), MipsTypes.psi("MACRO_PARAMETER") ->
                    if (element.node.elementType == MipsTypes.psi("MNEMONIC")) emptyList() else CONSTANT_KINDS
                in OPERAND_CONTEXTS -> if (element.node.elementType == MipsTypes.psi("MNEMONIC")) LABEL_KINDS else OPERAND_KINDS
                in EXPANSION_CONTEXTS -> if (element.node.elementType == MipsTypes.psi("MNEMONIC")) LABEL_KINDS else SymbolKind.entries
                else -> emptyList()
            }
        }
    }
}

internal object MipsReferenceExternalizer : DataExternalizer<List<MipsReferenceSite>> {
    override fun save(out: DataOutput, value: List<MipsReferenceSite>) {
        out.writeInt(value.size)
        for (site in value) {
            out.writeByte(site.kind.ordinal)
            out.writeInt(site.offset)
            out.writeInt(site.macroOffset)
        }
    }

    override fun read(input: DataInput): List<MipsReferenceSite> = List(input.readInt()) {
        MipsReferenceSite(SymbolKind.entries[input.readUnsignedByte()], input.readInt(), input.readInt())
    }
}
