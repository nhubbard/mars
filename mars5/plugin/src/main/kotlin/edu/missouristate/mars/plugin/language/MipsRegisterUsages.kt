package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType
import com.intellij.util.Consumer
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.OperandFormat
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsSourceTimeline
import edu.missouristate.mars.plugin.analysis.discardTrailingComment
import edu.missouristate.mars.plugin.analysis.sourceLine
import edu.missouristate.mars.plugin.analysis.sourceLineStart

internal class MipsRegisterUsages : HighlightUsagesHandlerFactoryBase(), DumbAware {
    override fun createHighlightUsagesHandler(
        editor: Editor, psiFile: PsiFile, target: PsiElement,
    ): HighlightUsagesHandlerBase<PsiElement>? {
        if (psiFile !is MipsFile || target.elementType !in usageTypes) return null
        val usages = if (DumbService.isDumb(psiFile.project)) {
            psiFile.writtenRegisterUsages()
        } else {
            psiFile.project.service<MarsAnalysisService>().editorTimeline(psiFile).registerUsages(psiFile)
        }
        val ranges = usages[target.textOffset] ?: return null
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
private val registerType = MipsTypes.psi("REGISTER")
private val mnemonicType = MipsTypes.psi("MNEMONIC")
private val aliasTypes = TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("DOLLAR_WORD"))
private val usageTypes = TokenSet.orSet(aliasTypes, TokenSet.create(registerType))
private val registerTokens = setOf(TokenTypes.REGISTER_NAME, TokenTypes.REGISTER_NUMBER, TokenTypes.FP_REGISTER_NAME)

private enum class Bank { INTEGER, COPROCESSOR_0, FLOATING }
private data class RegisterId(val bank: Bank, val number: Int)

private fun MipsFile.writtenRegisterUsages(): Map<Int, List<TextRange>> = CachedValuesManager.getCachedValue(this) {
    CachedValueProvider.Result.create(collectRegisterUsages(this, null), this)
}

internal fun collectRegisterUsages(file: MipsFile, timeline: MipsSourceTimeline?): Map<Int, List<TextRange>> {
    val groups = LinkedHashMap<RegisterId, MutableList<TextRange>>()
    for (statement in SyntaxTraverser.psiTraverser(file).filterTypes { it == statementType }) {
        ProgressManager.checkCanceled()
        val operation = statement.node.findChildByType(operationOwners)?.psi ?: continue
        val head = operation.operationHead
        val leaves = SyntaxTraverser.psiTraverser(statement).filterTypes { it in usageTypes }
        val start = sourceLineStart(head)
        val prefix = timeline?.prefix(file, start)
        if (prefix != null && prefix.openMacro == null &&
            leaves.any { it.elementType in aliasTypes && prefix.equivalence(it.text) != null }
        ) {
            for ((range, register) in projectedRegisters(file, head, start, prefix)) {
                groups.getOrPut(register, ::ArrayList).add(range)
            }
            continue
        }
        if (head.elementType != mnemonicType) continue
        val systemOperand = coprocessor0Operand(head)
        // An unfinished address can put its last register in a statement recovery node.
        for (token in leaves.filter { it.elementType == registerType }) {
            ProgressManager.checkCanceled()
            val register = registerId(token.text, token.textOffset == systemOperand) ?: continue
            groups.getOrPut(register, ::ArrayList).add(token.textRange)
        }
    }
    return buildMap {
        for (ranges in groups.values) {
            for (range in ranges) {
                ProgressManager.checkCanceled()
                put(range.startOffset, ranges)
            }
        }
    }
}

private fun projectedRegisters(
    file: MipsFile, head: PsiElement, start: Int, prefix: MipsSourceTimeline.Prefix,
): List<Pair<TextRange, RegisterId>> {
    val errors = ErrorList()
    val mapped = Tokenizer.tokenizeMappedLine(sourceLine(head, fromLineStart = true), errors,
        { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled)
    val tokens = mapped.tokens()
    if (errors.errorsOccurred()) return emptyList()
    tokens.discardTrailingComment()
    if (tokens.beginsWithLabel()) {
        tokens.remove(0)
        tokens.remove(0)
    }
    if (tokens.isEmpty || prefix.operationType(tokens, 0) != TokenTypes.OPERATOR) return emptyList()
    val form = InstructionDefaults.get().matchOperator(tokens[0].value)?.firstOrNull { OperandFormat.matches(tokens, it) }
        ?: return emptyList()
    val operation = mapped.originalOffset(tokens[0].startPos - 1)
    val byOffset = LinkedHashMap<Int, MutableSet<RegisterId>>()
    for (index in 1 until tokens.size()) {
        ProgressManager.checkCanceled()
        val token = tokens[index]
        if (token.type !in registerTokens) continue
        val offset = mapped.originalOffset(token.startPos - 1)
        if (offset == operation) continue
        val register = registerId(token.value, form.hasCoprocessor0Register && index == COPROCESSOR0_REGISTER_INDEX)
            ?: continue
        byOffset.getOrPut(offset, ::LinkedHashSet).add(register)
    }
    return byOffset.mapNotNull { (offset, registers) ->
        val register = registers.singleOrNull() ?: return@mapNotNull null
        file.findElementAt(start + offset)!!.textRange to register
    }
}

private fun registerId(text: String, systemOperand: Boolean): RegisterId? {
    val integer = RegisterNames.integer(text)
    if (systemOperand) return integer?.let { RegisterId(Bank.COPROCESSOR_0, it.number) }
    if (integer != null) return RegisterId(Bank.INTEGER, integer.number)
    val floating = RegisterNames.floating(text)!!
    // Tokenization recognizes alternate FPR numbers, but assembly requires the canonical name.
    return RegisterId(Bank.FLOATING, floating.number).takeIf { floating.name == text }
}
