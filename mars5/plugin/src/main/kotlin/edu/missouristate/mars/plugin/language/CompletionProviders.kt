package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.ElementPattern
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

/** Registers a native provider without changing pattern matching or completion scheduling. */
internal fun CompletionContributor.extendCompletion(
    type: CompletionType?,
    place: ElementPattern<out PsiElement>,
    complete: (CompletionParameters, ProcessingContext, CompletionResultSet) -> Unit,
) {
  extend(
      type,
      place,
      object : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) = complete(parameters, context, result)
      },
  )
}
