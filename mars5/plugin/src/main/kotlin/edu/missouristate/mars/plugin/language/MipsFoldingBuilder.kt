package edu.missouristate.mars.plugin.language

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet

internal class MipsFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (root !is MipsFile) return FoldingDescriptor.EMPTY_ARRAY
        val text = document.charsSequence
        val descriptors = ArrayList<FoldingDescriptor>()
        for (statement in root.node.getChildren(statements)) {
            ProgressManager.checkCanceled()
            val macro = statement.findChildByType(macroDefinition) ?: continue
            // Only a standalone terminator is a direct child of the macro. Malformed or replaced
            // terminators are part of body statements and must not delimit a fold.
            val closing = macro.findChildByType(endMacro) ?: continue
            val firstLine = document.getLineNumber(macro.startOffset)
            val closingLine = document.getLineNumber(closing.startOffset)
            if (closingLine <= firstLine + 1) continue
            val start = document.getLineEndOffset(firstLine)
            val end = document.getLineEndOffset(closingLine - 1)
            if (text.subSequence(start, end).isNotBlank()) {
                descriptors.add(FoldingDescriptor(macro, TextRange(start, end)))
            }
        }
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode) = " …"
    override fun isCollapsedByDefault(node: ASTNode) = false

    private companion object {
        val statements = TokenSet.create(MipsTypes.psi("STATEMENT"))
        val macroDefinition = MipsTypes.psi("MACRO_DEFINITION")
        val endMacro = MipsTypes.psi("END_MACRO")
    }
}
