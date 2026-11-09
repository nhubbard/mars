package edu.missouristate.mars.plugin.language.format

import com.intellij.formatting.Block
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.DocumentBasedFormattingModel
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilCore
import edu.missouristate.mars.plugin.language.MipsTypes

internal class MipsFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val root = MipsBlock(file.node, Indent.getNoneIndent(), file.viewProvider.contents)
        return DocumentBasedFormattingModel(root, formattingContext.codeStyleSettings, file)
    }
}

private class MipsBlock(node: ASTNode, private val indent: Indent, private val source: CharSequence) :
    AbstractBlock(node, null, null) {

    override fun getIndent() = indent

    override fun isLeaf() = myNode.firstChildNode == null || myNode.elementType in opaque

    override fun buildChildren(): List<Block> =
        if (myNode.elementType in opaque) emptyList() else myNode.getChildren(null).asSequence()
            .filter { it.textLength > 0 && it.elementType !in trivia }
            .map { MipsBlock(it, indentOf(it), source) }
            .toList()

    override fun getChildIndent(): Indent = if (myNode.elementType in indentedScopes) {
        Indent.getNormalIndent()
    } else Indent.getNoneIndent()

    private fun indentOf(child: ASTNode): Indent {
        if (child.elementType == statement && (myNode.elementType == macro ||
                PsiUtilCore.getElementType(child.firstChildNode) in executableHeads)) {
            return Indent.getNormalIndent()
        }
        return Indent.getNoneIndent()
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 == null) return null
        val left = (child1 as MipsBlock).node
        val right = (child2 as MipsBlock).node
        val before = PsiUtilCore.getElementType(TreeUtil.findLastLeaf(left))
        val after = PsiUtilCore.getElementType(TreeUtil.findFirstLeaf(right))
        val gap = source.subSequence(left.textRange.endOffset, right.startOffset)
        return when {
            gap.any { it !in " \t\r\n" } -> readOnly
            StringUtil.containsLineBreak(gap) -> keepLines
            left.elementType in protectedSpacing || right.elementType in protectedSpacing -> readOnly
            // A space can turn a sign into a separate token, or prevent an exponent from joining.
            before in signs -> readOnly
            after in signs -> if (gap.length == 0) readOnly else oneSpace
            after in closingPunctuation || before == leftParen -> noSpaces
            after == leftParen && myNode.elementType == address -> noSpaces
            before == colon && myNode.elementType == repetition -> noSpaces
            before in separators || after == comment -> oneSpace
            gap.isNotEmpty() -> oneSpace
            else -> readOnly
        }
    }

    private companion object {
        val newline = MipsTypes.psi("NEWLINE")
        val statement = MipsTypes.psi("STATEMENT")
        val macro = MipsTypes.psi("MACRO_DEFINITION")
        val replacement = MipsTypes.psi("REPLACEMENT")
        val address = MipsTypes.psi("ADDRESS_EXPR")
        val repetition = MipsTypes.psi("REPETITION")
        val comment = MipsTypes.psi("COMMENT")
        val leftParen = MipsTypes.psi("LEFT_PAREN")
        val colon = MipsTypes.psi("COLON")
        val comma = MipsTypes.psi("COMMA")
        val trivia = TokenSet.create(TokenType.WHITE_SPACE, newline)
        val signs = TokenSet.create(MipsTypes.psi("PLUS"), MipsTypes.psi("MINUS"))
        val closingPunctuation = TokenSet.create(comma, colon, MipsTypes.psi("RIGHT_PAREN"))
        val separators = TokenSet.create(comma, colon)
        val indentedScopes = TokenSet.create(MipsTypes.file, macro)
        val candidate = MipsTypes.psi("OPERATION_CANDIDATE")
        val executableHeads = TokenSet.create(MipsTypes.psi("INSTRUCTION"), candidate, MipsTypes.psi("DATA_CONTINUATION"))
        val protectedSpacing = TokenSet.create(replacement, TokenType.ERROR_ELEMENT)
        // A raw operation can be an equivalence-generated directive or a macro invocation.
        val opaque = TokenSet.create(candidate, replacement, MipsTypes.psi("OPERAND_FRAGMENT"),
            MipsTypes.psi("INCLUDE_LINE"), TokenType.ERROR_ELEMENT)

        val readOnly: Spacing = Spacing.getReadOnlySpacing()
        val noSpaces: Spacing = Spacing.createSpacing(0, 0, 0, false, 0)
        val oneSpace: Spacing = Spacing.createSpacing(1, 1, 0, false, 0)
        // The formatter adds one line feed to its blank-line limit.
        val keepLines: Spacing = Spacing.createSpacing(0, 0, 0, true, Int.MAX_VALUE - 1)
    }
}
