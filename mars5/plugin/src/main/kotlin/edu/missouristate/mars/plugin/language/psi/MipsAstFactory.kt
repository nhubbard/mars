package edu.missouristate.mars.plugin.language.psi

import com.intellij.lang.ASTFactory
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import edu.missouristate.mars.plugin.language.MipsTypes

class MipsAstFactory : ASTFactory() {
    override fun createLeaf(type: IElementType, text: CharSequence): LeafElement? = when (type) {
        MipsTypes.psi("STRING") -> MipsStringLiteral(type, text)
        in symbolTokens -> MipsSymbolToken(type, text)
        else -> null
    }

    private companion object {
        val symbolTokens = TokenSet.create(
            MipsTypes.psi("WORD"), MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("MNEMONIC"), MipsTypes.psi("PARAMETER"),
        )
    }
}
