package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.util.ProcessingContext
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.plugin.analysis.LabelCompletionContext
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.language.psi.DataLabelOperand
import edu.missouristate.mars.plugin.language.psi.macroParameterUseContexts

class MipsCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, statementHead, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val position = parameters.position
                val file = parameters.originalFile as MipsFile
                val prefix = prefix(parameters)
                val lookup = result.withPrefixMatcher(prefix)
                val options = file.project.service<MarsAssemblySettings>().executionOptions
                val items = MipsDocumentationCatalog.entries.mapNotNull { entry ->
                    val tail = entry.completionTail(options.allowPseudoInstructions, options.bareMachine)
                    tail?.let { LookupElementBuilder.create(entry, entry.name).withTailText(it, true) }
                }
                lookup.caseInsensitive().addAllElements(items)
                val range = TextRange(position.textOffset, replacementEnd(parameters))
                for ((name, replacement) in statementEquivalenceCompletions(file, range, prefix, options)) {
                    lookup.addElement(equivalenceLookup(name, replacement))
                }
                val macros = file.project.service<MarsAnalysisService>().macroCompletionSignatures(file, position.textOffset, prefix)
                for ((name, signatures) in macros) {
                    lookup.addElement(LookupElementBuilder.create(name).withTailText(signatures.joinToString(" / ", prefix = " "), true))
                }
            }
        })
        extend(CompletionType.BASIC, operandOrContinuationPosition, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val position = parameters.position
                val root = parameters.originalFile as MipsFile
                val options = root.project.service<MarsAssemblySettings>().executionOptions
                val operand = if (!statementHead.accepts(position)) {
                    MipsInstructionOperand.at(position, root, options.allowPseudoInstructions)
                } else null
                val analysis = root.project.service<MarsAnalysisService>()
                val prefix = prefix(parameters)
                val lookup = result.withPrefixMatcher(prefix)
                val end = replacementEnd(parameters)
                val data = if (operand == null) DataLabelOperand.at(position) else null
                val export = data?.exportHead(position)?.let { analysis.exportOperandsBefore(root, it, position) }
                val range = TextRange(position.textOffset, end)
                val equivalences = when {
                    operand != null -> {
                        lookup.addAllElements(registerCandidates(operand.formats, operand.index, options.bareMachine).map(LookupElementBuilder::create))
                        operand.equivalenceCompletions(root, prefix, options.bareMachine, end)
                    }
                    export != null -> analysis.exportEquivalenceCompletions(root, position, range, prefix, export.size())
                    else -> analysis.directiveOperandEquivalenceCompletions(root, range, prefix)
                }
                for ((name, replacement) in equivalences) {
                    lookup.addElement(equivalenceLookup(name, replacement))
                }
                if (export != null) {
                    val written = analysis.writtenExportNames(root, range, export)
                    val names = analysis.labelCompletionNames(root, position, prefix, LabelCompletionContext.EXPORT)
                        .filter { it !in written && TokenTypes.matchTokenType(it) == TokenTypes.IDENTIFIER }
                    lookup.addAllElements(names.map(LookupElementBuilder::create))
                } else if (operand?.acceptsLabel == true || data?.isDataLabel(root, analysis, position) == true) {
                    val labelContext = if (operand == null) LabelCompletionContext.SOURCE else LabelCompletionContext.INSTRUCTION
                    val names = analysis.labelCompletionNames(root, position, prefix, labelContext)
                    lookup.addAllElements(names.map(LookupElementBuilder::create))
                }
            }
        })
        extend(CompletionType.BASIC, macroParameterPosition, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val prefix = prefix(parameters)
                if (prefix.isNotEmpty() && prefix.first() !in "%$") return
                val file = parameters.originalFile as MipsFile
                val names = file.project.service<MarsAnalysisService>().macroParameterNames(file, parameters.position)
                result.withPrefixMatcher(prefix).addAllElements(
                    names.filter { it.startsWith(prefix) }.map {
                        LookupElementBuilder.create(it).withTypeText(messages.getMessage("completion.macro.parameter"))
                    },
                )
            }
        })
    }

    override fun duringCompletion(context: CompletionInitializationContext) {
        val token = context.file.findElementAt(context.startOffset) ?: return
        if (statementHead.accepts(token) || operandOrContinuationPosition.accepts(token) || macroParameterPosition.accepts(token)) {
            context.setReplacementOffset(token.textRange.endOffset)
        }
    }

    private companion object {
        val messages = DynamicBundle(MipsCompletionContributor::class.java, "messages.MarsBundle")
        val statementHead = psiElement()
            .withElementType(TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("MNEMONIC"), MipsTypes.psi("DOLLAR_WORD")))
            .withParent(psiElement().withElementType(TokenSet.create(MipsTypes.psi("OPERATION"), MipsTypes.psi("OPERATION_CANDIDATE"))))
        val operandOrContinuationPosition = psiElement()
            .withElementType(TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("REGISTER")))
        val macroParameterPosition = psiElement()
            .withElementType(TokenSet.create(MipsTypes.psi("PARAMETER"), MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("WORD")))
            .withParent(psiElement().withElementType(macroParameterUseContexts))

        val integerRegisters by lazy { RegisterNames.integerSpellings() }
        val registerSpellings by lazy {
            mapOf(
                TokenTypes.REGISTER_NAME to integerRegisters,
                TokenTypes.REGISTER_NUMBER to integerRegisters,
                TokenTypes.FP_REGISTER_NAME to RegisterNames.floatingSpellings(),
            )
        }
        val systemRegisters by lazy { RegisterNames.coprocessor0Spellings() }
        val integerRegisterTypes = setOf(TokenTypes.REGISTER_NAME, TokenTypes.REGISTER_NUMBER)
        val numberedIntegerRegisters by lazy {
            integerRegisters.filter { TokenTypes.matchTokenType(it) == TokenTypes.REGISTER_NUMBER }
        }

        fun prefix(parameters: CompletionParameters): String {
            val position = parameters.position
            return position.text.substring(0, parameters.offset - position.textOffset)
        }

        fun replacementEnd(parameters: CompletionParameters): Int = parameters.originalPosition
            ?.takeIf { statementHead.accepts(it) || operandOrContinuationPosition.accepts(it) }
            ?.let { it.textRange.endOffset } ?: parameters.position.textOffset

        fun equivalenceLookup(name: String, replacement: String): LookupElementBuilder =
            LookupElementBuilder.create(name).withTailText(" = " + replacement, true)
                .withTypeText(messages.getMessage("colors.equivalence"))

        fun registerCandidates(formats: List<Instruction>, index: Int, bareMachine: Boolean): Set<String> = buildSet {
            for (instruction in formats) {
                if (index == COPROCESSOR0_REGISTER_INDEX && instruction.hasCoprocessor0Register) {
                    addAll(systemRegisters)
                } else {
                    val type = instruction.tokenList[index].type
                    addAll(if (bareMachine && type in integerRegisterTypes) numberedIntegerRegisters else registerSpellings[type].orEmpty())
                }
            }
        }
    }
}
