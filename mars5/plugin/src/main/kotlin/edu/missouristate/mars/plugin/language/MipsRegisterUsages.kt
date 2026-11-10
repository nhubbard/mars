package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
import com.intellij.util.Consumer
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.macroLineTokens

internal class MipsRegisterUsages : HighlightUsagesHandlerFactoryBase(), DumbAware {
    override fun createHighlightUsagesHandler(
        editor: Editor, psiFile: PsiFile, target: PsiElement,
    ): HighlightUsagesHandlerBase<PsiElement>? {
        if (psiFile !is MipsFile || target.elementType != registerType) return null
        val ranges = psiFile.writtenRegisterUsages()[target.textOffset] ?: return null
        return RegisterUsagesHandler(editor, psiFile, target, ranges)
    }
}

private class RegisterUsagesHandler(
    editor: Editor, file: MipsFile, private val target: PsiElement, private val ranges: List<TextRange>,
) : HighlightUsagesHandlerBase<PsiElement>(editor, file), DumbAware {
    override fun getTargets(): List<PsiElement> = listOf(target)

    override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) {
        selectionConsumer.consume(targets)
    }

    override fun computeUsages(targets: List<PsiElement>) {
        myReadUsages.addAll(ranges)
        buildStatusText(target.text, ranges.size)
    }
}

private val statementType = MipsTypes.psi("STATEMENT")
private val instructionType = MipsTypes.psi("INSTRUCTION")
private val registerType = MipsTypes.psi("REGISTER")
private val coprocessor0Transfers by lazy {
    InstructionDefaults.get().instructionList.filter { it.hasCoprocessor0Register }.map { it.name }.toSet()
}

private enum class Bank { INTEGER, COPROCESSOR_0, FLOATING }
private data class RegisterId(val bank: Bank, val number: Int)

private fun MipsFile.writtenRegisterUsages(): Map<Int, List<TextRange>> = CachedValuesManager.getCachedValue(this) {
    val groups = LinkedHashMap<RegisterId, MutableList<TextRange>>()
    for (statement in SyntaxTraverser.psiTraverser(this).filterTypes { it == statementType }) {
        ProgressManager.checkCanceled()
        val instruction = statement.node.findChildByType(instructionType)?.psi ?: continue
        val head = instruction.operationHead
        val systemOperand = coprocessor0Operand(head)
        // An unfinished address can put its last register in a statement recovery node.
        for (token in SyntaxTraverser.psiTraverser(statement).filterTypes { it == registerType }) {
            ProgressManager.checkCanceled()
            val register = registerId(token, systemOperand) ?: continue
            groups.getOrPut(register, ::ArrayList).add(token.textRange)
        }
    }
    val offsets = buildMap {
        for (ranges in groups.values) {
            for (range in ranges) {
                ProgressManager.checkCanceled()
                put(range.startOffset, ranges)
            }
        }
    }
    CachedValueProvider.Result.create(offsets, this)
}

private fun coprocessor0Operand(head: PsiElement): Int? {
    if (head.text.lowercase() !in coprocessor0Transfers) return null
    val tokens = macroLineTokens(head)
    if (tokens.size() <= COPROCESSOR0_REGISTER_INDEX) return null
    return head.textOffset + tokens[COPROCESSOR0_REGISTER_INDEX].startPos - 1
}

private fun registerId(token: PsiElement, systemOperand: Int?): RegisterId? {
    val text = token.text
    val integer = RegisterNames.integer(text)
    if (token.textOffset == systemOperand) return integer?.let { RegisterId(Bank.COPROCESSOR_0, it.number) }
    if (integer != null) return RegisterId(Bank.INTEGER, integer.number)
    val floating = RegisterNames.floating(text)!!
    // Tokenization recognizes alternate FPR numbers, but assembly requires the canonical name.
    return RegisterId(Bank.FLOATING, floating.number).takeIf { floating.name == text }
}
