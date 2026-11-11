package edu.missouristate.mars.plugin.language.psi

import com.intellij.DynamicBundle
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import com.intellij.util.IncorrectOperationException
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.MipsTypes

internal class MipsDeclaration(node: ASTNode, val kind: SymbolKind) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier() =
        if (kind == SymbolKind.EXTERNAL) firstChild else node.findChildByType(NAMES)?.psi

    override fun getName(): String? {
        val identifier = nameIdentifier ?: return null
        return when (identifier.elementType) {
            PARAMETER -> null
            CHARACTER -> Tokenizer.decodeCharacterLiteral(identifier.text).toString()
            else -> identifier.text
        }
    }
    override fun getTextOffset() = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getPresentation() = PresentationData(
        name, containingFile.name, when (kind) {
            SymbolKind.LABEL -> AllIcons.Nodes.Bookmark
            SymbolKind.MACRO -> AllIcons.Nodes.Function
            SymbolKind.CONSTANT -> AllIcons.Nodes.Constant
            SymbolKind.EXTERNAL -> AllIcons.Nodes.Field
        }, null,
    )

    override fun setName(name: String): PsiElement =
        throw IncorrectOperationException(bundle.getMessage("symbol.rename.unavailable"))

    companion object {
        private val bundle = DynamicBundle(MipsDeclaration::class.java, "messages.MarsBundle")
        private val PARAMETER = MipsTypes.psi("PARAMETER")
        private val CHARACTER = MipsTypes.psi("CHARACTER")
        private val EXTERNAL_SIGNED_NAME = MipsTypes.psi("EXTERNAL_SIGNED_NAME")
        private val NAMES = TokenSet.create(
            MipsTypes.psi("WORD"), MipsTypes.psi("MNEMONIC"), MipsTypes.psi("DIRECTIVE"),
            MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("REGISTER"), MipsTypes.psi("SET"),
        )
        // An indexed offset may outlive the PSI it described. Verify the current identifier.
        fun findAt(file: PsiFile?, offset: Int, name: String): MipsDeclaration? {
            val leaf = file?.findElementAt(offset) ?: return null
            val identifier = if (leaf.parent.elementType == EXTERNAL_SIGNED_NAME) leaf.parent else leaf
            val declaration = identifier.parent as? MipsDeclaration
            return declaration?.takeIf { it.nameIdentifier === identifier && it.textOffset == offset && it.name == name }
        }
    }
}
