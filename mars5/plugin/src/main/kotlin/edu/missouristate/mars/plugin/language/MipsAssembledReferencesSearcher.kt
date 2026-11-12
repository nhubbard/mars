package edu.missouristate.mars.plugin.language

import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch.SearchParameters
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.Processor
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.assembler.Token
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.analysis.sourceLineStart
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.syntax.MipsScanner

/** Supplements IntelliJ's word search with bindings established during assembly. */
internal class MipsAssembledReferencesSearcher : QueryExecutorBase<PsiReference, SearchParameters>(true) {
    override fun processQuery(queryParameters: SearchParameters, consumer: Processor<in PsiReference>) {
        val target = queryParameters.elementToSearch as? MipsDeclaration ?: return
        if (target.kind == SymbolKind.CONSTANT) return
        val analysis = target.project.service<MarsAnalysisService>()
        val scope = queryParameters.effectiveSearchScope
        for (site in analysis.assembledUsages(target)) {
            ProgressManager.checkCanceled()
            val reference = analysis.assembledReference(target, site, scope) ?: continue
            if (target.kind != SymbolKind.MACRO && reference.element.references.any { it.isReferenceTo(target) }) continue
            if (!consumer.process(reference)) return
        }
    }
}

/** Adds navigable, read-only occurrences to Find Usages without exposing them to refactoring. */
internal class MipsExcerptUsageSearcher : CustomUsageSearcher() {
    override fun processElementUsages(element: PsiElement, processor: Processor<in Usage>, options: FindUsagesOptions) {
        val target = element as? MipsDeclaration ?: return
        if (!options.isUsages || target.kind == SymbolKind.CONSTANT) return
        ReadAction.computeBlocking<Unit, RuntimeException> {
            val analysis = target.project.service<MarsAnalysisService>()
            for ((file, offset) in analysis.assembledExcerptUsages(target, options.searchScope)) {
                val reference = assembledReferenceAt(target, file.findElementAt(offset)!!, offset)
                if (!processor.process(UsageInfo2UsageAdapter(UsageInfo(reference)))) break
            }
        }
    }
}

internal fun MarsAnalysisService.assembledReference(target: MipsDeclaration, site: SourceToken, scope: SearchScope): PsiReference? {
    val (file, offset) = sourceTokenPosition(site) ?: return null
    val leaf = file.findElementAt(offset)!!
    if (!PsiSearchScopeUtil.isInScope(scope, leaf)) return null
    return assembledReferenceAt(target, leaf, offset)
}

private fun assembledReferenceAt(target: MipsDeclaration, leaf: PsiElement, offset: Int): PsiReference {
    val file = leaf.containingFile
    val text = file.viewProvider.contents
    val editorEnd = if (file is MipsFile) leaf.textRange.endOffset else MipsScanner.next(text, offset)!!.end
    if (target.usesAssemblerSpelling()) {
        val end = assemblerTokensStartingAt(text, offset).fold(editorEnd) { _, token ->
            offset + token.endPos - token.startPos
        }
        val anchor = PsiTreeUtil.findCommonParent(leaf, file.findElementAt(end - 1)!!)!!
        return PsiReferenceBase.Immediate(anchor, TextRange(offset, end).shiftLeft(anchor.textRange.startOffset), target)
    }
    return PsiReferenceBase.Immediate(leaf, TextRange(offset, editorEnd).shiftLeft(leaf.textRange.startOffset), target)
}

internal fun MipsDeclaration.usesAssemblerSpelling(): Boolean =
    kind == SymbolKind.EXTERNAL && !TokenTypes.isValidIdentifier(nameIdentifier!!.text)

/** Signed names can span PSI leaves, and a character literal's value differs from its source text. */
internal fun assemblerTokensStartingAt(text: CharSequence, offset: Int): Sequence<Token> {
    val start = sourceLineStart(text, offset)
    val end = CharArrayUtil.shiftForwardUntil(text, offset, "\r\n")
    val tokens = Tokenizer().tokenizeLine(null, 1, text.subSequence(start, end).toString(), false)
    return (0 until tokens.size()).asSequence().map(tokens::get).filter { start + it.startPos - 1 == offset }
}
