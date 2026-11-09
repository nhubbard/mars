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
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.ProcessingContext
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.mips.instructions.basic.InstrMfc0
import edu.missouristate.mars.mips.instructions.basic.InstrMtc0
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService

class MipsCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, statementHead, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                result.withPrefixMatcher(prefix(parameters)).caseInsensitive().addAllElements(catalog.map { (name, tail) ->
                    LookupElementBuilder.create(name).withTailText(tail, true)
                })
            }
        })
        extend(CompletionType.BASIC, operandPosition, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val position = parameters.position
                val instruction = PsiTreeUtil.findFirstParent(position, true) {
                    PsiUtilCore.getElementType(it) == instructionType
                } ?: return
                val operation = instruction.firstChild
                val text = parameters.editor.document.immutableCharSequence
                val before = text.subSequence(operation.textOffset, position.textOffset).toString()
                val tokens = Tokenizer().tokenizeLine(1, before, ErrorList(), false)
                val index = tokens.size()
                val formats = InstructionDefaults.get().matchOperator(operation.text).filter {
                    index < it.tokenList.size() && matchesAddressShape(tokens, it.tokenList)
                }
                val prefix = prefix(parameters)
                val lookup = result.withPrefixMatcher(prefix)
                lookup.addAllElements(registerCandidates(formats, index).map(LookupElementBuilder::create))
                if (formats.any { it.tokenList[index].type == TokenTypes.IDENTIFIER }) {
                    val root = parameters.originalFile as MipsFile
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
        val instructionType = MipsTypes.psi("INSTRUCTION")
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
        val addressPunctuation = setOf(TokenTypes.LEFT_PAREN, TokenTypes.RIGHT_PAREN)

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

        fun matchesAddressShape(tokens: TokenList, format: TokenList): Boolean = (1 until tokens.size()).all { index ->
            val actual = tokens[index].type
            val expected = format[index].type
            actual == expected || (actual !in addressPunctuation && expected !in addressPunctuation)
        }

        val catalog by lazy {
            // IntelliJ attaches lookup-session data to items. Cache only the catalog text.
            val instructions = InstructionDefaults.get().instructionList.distinctBy { it.name }.map {
                it.name to it.exampleFormat.removePrefix(it.name)
            }
            instructions + Directives.getDirectiveList().map {
                it.name to "  ${it.description}"
            }
        }
    }
}
