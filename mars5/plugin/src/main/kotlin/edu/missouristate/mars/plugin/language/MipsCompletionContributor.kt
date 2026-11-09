package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.util.ProcessingContext
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.basic.InstrMfc0
import edu.missouristate.mars.mips.instructions.basic.InstrMtc0
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService

class MipsCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, statementHead, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val items = MipsDocumentationCatalog.entries.map { entry ->
                    LookupElementBuilder.create(entry, entry.name).withTailText(entry.completionTail, true)
                }
                result.withPrefixMatcher(prefix(parameters)).caseInsensitive().addAllElements(items)
            }
        })
        extend(CompletionType.BASIC, operandPosition, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val position = parameters.position
                val root = parameters.originalFile as MipsFile
                val operand = MipsInstructionOperand.at(position, root) ?: return
                val prefix = prefix(parameters)
                val lookup = result.withPrefixMatcher(prefix)
                lookup.addAllElements(registerCandidates(operand.formats, operand.index).map(LookupElementBuilder::create))
                if (operand.acceptsLabel) {
                    val names = root.project.service<MarsAnalysisService>().labelCompletionNames(root, position, prefix)
                    lookup.addAllElements(names.map(LookupElementBuilder::create))
                }
            }
        })
    }

    override fun duringCompletion(context: CompletionInitializationContext) {
        val token = context.file.findElementAt(context.startOffset) ?: return
        if (statementHead.accepts(token) || operandPosition.accepts(token)) {
            context.setReplacementOffset(token.textRange.endOffset)
        }
    }

    private companion object {
        val statementHead = psiElement()
            .withElementType(TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("MNEMONIC")))
            .withParent(psiElement().withElementType(TokenSet.create(MipsTypes.psi("OPERATION"), MipsTypes.psi("OPERATION_CANDIDATE"))))
        val operandPosition = psiElement()
            .withElementType(TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("REGISTER")))
            .andNot(statementHead)

        val registerSpellings by lazy {
            val integer = RegisterNames.integerSpellings()
            mapOf(
                TokenTypes.REGISTER_NAME to integer,
                TokenTypes.REGISTER_NUMBER to integer,
                TokenTypes.FP_REGISTER_NAME to RegisterNames.floatingSpellings(),
            )
        }
        val systemRegisters by lazy { RegisterNames.coprocessor0Spellings() }
        val systemTransfers: Set<Class<out Instruction>> = setOf(InstrMfc0::class.java, InstrMtc0::class.java)

        fun prefix(parameters: CompletionParameters): String {
            val position = parameters.position
            return position.text.substring(0, parameters.offset - position.textOffset)
        }

        fun registerCandidates(formats: List<Instruction>, index: Int): Set<String> = buildSet {
            for (instruction in formats) {
                // The core tokenizes the second operand of a CP0 transfer as a numeric GPR.
                if (index == 2 && instruction.javaClass in systemTransfers) {
                    addAll(systemRegisters)
                } else {
                    addAll(registerSpellings[instruction.tokenList[index].type].orEmpty())
                }
            }
        }
    }
}
