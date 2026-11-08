package edu.missouristate.mars.plugin.language.psi

import com.intellij.lang.ASTFactory
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType
import edu.missouristate.mars.plugin.language.MipsTypes

class MipsAstFactory : ASTFactory() {
    override fun createLeaf(type: IElementType, text: CharSequence): LeafElement? =
        if (type == MipsTypes.psi("STRING")) MipsStringLiteral(type, text) else null
}
