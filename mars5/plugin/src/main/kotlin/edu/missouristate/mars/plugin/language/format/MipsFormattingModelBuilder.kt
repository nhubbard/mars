package edu.missouristate.mars.plugin.language.format

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.lang.ASTNode
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.DocumentBasedFormattingModel
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.FormattingLine
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsSourceTimeline
import edu.missouristate.mars.plugin.analysis.OperandParenthesis
import edu.missouristate.mars.plugin.analysis.sourceLineStart
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.operationHead
import edu.missouristate.mars.plugin.language.operationOwners

internal class MipsFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile as MipsFile
        val analysis = file.project.service<MarsAnalysisService>()
        val timeline = lazy(LazyThreadSafetyMode.NONE) {
            if (DumbService.isDumb(file.project)) null else analysis.editorTimeline(file)
        }
        val root = MipsBlock(file.node, Indent.getNoneIndent(), file.viewProvider.contents, analysis, timeline)
        return DocumentBasedFormattingModel(root, formattingContext.codeStyleSettings, file)
    }
}

private class MipsBlock(
    node: ASTNode,
    private val indent: Indent,
    private val source: CharSequence,
    private val analysis: MarsAnalysisService,
    private val timeline: Lazy<MipsSourceTimeline?>,
    private val line: FormattingLine? = null,
    alignment: Alignment? = null,
) : AbstractBlock(node, null, alignment) {
    private val rawOperation = line?.operationType.takeIf { node.elementType == candidate }
    private val hasSemanticMacroHeader by lazy(LazyThreadSafetyMode.NONE) {
        val context = timeline.value ?: return@lazy false
        val headerEnd = CharArrayUtil.shiftForwardUntil(source, myNode.startOffset, "\r\n")
        context.macroAt(myNode.psi.containingFile as MipsFile, headerEnd) != null
    }

    private fun isOpaque() = myNode.elementType in opaque ||
        myNode.elementType == candidate && rawOperation !in formatOperations ||
        myNode.elementType == operandFragment && line?.operandParentheses == null

    override fun getIndent() = indent

    override fun isLeaf() = myNode.firstChildNode == null || isOpaque()

    override fun buildChildren(): List<Block> {
        if (isOpaque()) return emptyList()
        var fileIndentAlignment: Alignment? = null
        return myNode.getChildren(null).asSequence()
            .filter { it.textLength > 0 && it.elementType !in trivia }
            .mapIndexed { index, child ->
                val childLine = if (child.elementType == statement) layoutOf(child) else line
                val childIndent = indentOf(child, childLine)
                val indented = childIndent.type == Indent.Type.NORMAL
                // An indented first line also indents the file block's anchor.
                if (myNode.elementType == MipsTypes.file && index == 0 && indented) {
                    fileIndentAlignment = Alignment.createAlignment()
                }
                val alignment = fileIndentAlignment.takeIf { indented }
                MipsBlock(child, childIndent, source, analysis, timeline, childLine, alignment)
            }
            .toList()
    }

    override fun getChildIndent(): Indent = if (myNode.elementType in indentedScopes) {
        Indent.getNormalIndent()
    } else Indent.getNoneIndent()

    private fun layoutOf(child: ASTNode): FormattingLine? {
        val operation = child.findChildByType(operationOwners)
        val hasFragment = child.findChildByType(operandOwners)?.findChildByType(operandFragment) != null
        val anchor = operation?.psi?.operationHead ?: child.psi
        val context = timeline.value ?: return null
        val file = anchor.containingFile as MipsFile
        val hasParenthesis = operation != null && child.textContains('(')
        val prefix = context.prefix(file, sourceLineStart(anchor))
        val hasAlias = (hasParenthesis || context.hasQuotedEquivalence) && hasVisibleAlias(child, prefix)
        val projectOperands = hasFragment || hasParenthesis && hasAlias
        if (operation?.elementType != candidate && !projectOperands && !hasAlias &&
            prefix.openMacro == null && anchor !in context.generatedLabelSites) return null
        return analysis.formattingLine(file, anchor, context, projectOperands)
    }

    private fun hasVisibleAlias(child: ASTNode, prefix: MipsSourceTimeline.Prefix): Boolean =
        SyntaxTraverser.astTraverser(child).filterTypes { it in aliasTokens }.any {
            prefix.equivalence(it.text) != null
        }

    private fun indentOf(child: ASTNode, childLine: FormattingLine?): Indent {
        if (child.elementType != statement) return Indent.getNoneIndent()
        val context = timeline.value
        if (context != null) {
            val file = child.psi.containingFile as MipsFile
            val lineStart = sourceLineStart(source, child.startOffset)
            if (context.closesMacroOnLine(file, lineStart)) return Indent.getAbsoluteNoneIndent()
            if (context.macroAt(file, lineStart) != null) return Indent.getNormalIndent()
        }
        // An incomplete header can still give its body a physical PSI scope.
        if (myNode.elementType == macro && !hasSemanticMacroHeader ||
            PsiUtilCore.getElementType(child.firstChildNode) in executableHeads && childLine?.atMargin != true
        ) {
            return Indent.getNormalIndent()
        }
        return Indent.getAbsoluteNoneIndent()
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 == null) return null
        val left = (child1 as MipsBlock).node
        val right = (child2 as MipsBlock).node
        val before = PsiUtilCore.getElementType(TreeUtil.findLastLeaf(left))
        val after = PsiUtilCore.getElementType(TreeUtil.findFirstLeaf(right))
        val gap = source.subSequence(left.textRange.endOffset, right.startOffset)
        val expandedParenthesis = line?.operandParentheses?.get(right.startOffset)
        return when {
            StringUtil.containsLineBreak(gap) -> keepLines
            left.elementType in protectedSpacing || right.elementType in protectedSpacing -> readOnly
            line?.verbatimOperandRanges.orEmpty().any { it.startOffset < left.textRange.endOffset && right.startOffset < it.endOffset } -> readOnly
            // A space can turn a sign into a separate token, or prevent an exponent from joining.
            before in signs -> readOnly
            after in signs -> if (gap.length == 0) readOnly else oneSpace
            after in closingPunctuation || before == leftParen -> noSpaces
            after == leftParen && expandedParenthesis == OperandParenthesis.ADDRESS -> noSpaces
            after == leftParen && expandedParenthesis == OperandParenthesis.GROUP -> oneSpace
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
        val operandFragment = MipsTypes.psi("OPERAND_FRAGMENT")
        val aliasTokens = TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("DOLLAR_WORD"))
        val operandOwners = TokenSet.orSet(operationOwners,
            TokenSet.create(MipsTypes.psi("DIRECTIVE_STATEMENT"), MipsTypes.psi("DATA_CONTINUATION")))
        val executableHeads = TokenSet.create(MipsTypes.psi("INSTRUCTION"), candidate, MipsTypes.psi("DATA_CONTINUATION"))
        val protectedSpacing = TokenSet.create(replacement, TokenType.ERROR_ELEMENT)
        val formatOperations = setOf(TokenTypes.OPERATOR, TokenTypes.IDENTIFIER, TokenTypes.DIRECTIVE)
        // A physical alias can represent several tokens. Apply spacing only at written boundaries.
        val opaque = TokenSet.create(replacement, MipsTypes.psi("INCLUDE_LINE"), TokenType.ERROR_ELEMENT)

        val readOnly: Spacing = Spacing.getReadOnlySpacing()
        val noSpaces: Spacing = Spacing.createSpacing(0, 0, 0, false, 0)
        val oneSpace: Spacing = Spacing.createSpacing(1, 1, 0, false, 0)
        // The formatter adds one line feed to its blank-line limit.
        val keepLines: Spacing = Spacing.createSpacing(0, 0, 0, true, Int.MAX_VALUE - 1)
    }
}
