package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.util.ProcessingContext
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.mips.instructions.InstructionDefaults

class MipsCompletionContributor : CompletionContributor(), DumbAware {
    init {
        extend(CompletionType.BASIC, statementHead, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val position = parameters.position
                val prefix = position.text.substring(0, parameters.offset - position.textRange.startOffset)
                result.withPrefixMatcher(prefix).caseInsensitive().addAllElements(catalog.map { (name, tail) ->
                    LookupElementBuilder.create(name).withTailText(tail, true)
                })
            }
        })
    }

    override fun duringCompletion(context: CompletionInitializationContext) {
        val token = context.file.findElementAt(context.startOffset) ?: return
        if (statementHead.accepts(token)) {
            context.setReplacementOffset(token.textRange.endOffset)
        }
    }

    private companion object {
        val statementHead = psiElement()
            .withElementType(TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("MNEMONIC")))
            .withParent(psiElement().withElementType(TokenSet.create(MipsTypes.psi("OPERATION"), MipsTypes.psi("OPERATION_CANDIDATE"))))

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
