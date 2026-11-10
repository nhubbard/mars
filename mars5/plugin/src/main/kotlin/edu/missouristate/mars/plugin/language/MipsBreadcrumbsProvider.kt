package edu.missouristate.mars.plugin.language

import com.intellij.lang.Language
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.util.TreeMap

internal class MipsBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(MipsLanguage)

    override fun acceptElement(element: PsiElement): Boolean =
        element is MipsFile || element.elementType == labelType || element.elementType == macroSignature || element.isSegment()

    override fun getElementInfo(element: PsiElement): String = when (element) {
        is MipsFile -> element.name
        is MipsDeclaration -> element.name ?: element.firstChild.text
        else -> element.text
    }

    override fun getParent(element: PsiElement): PsiElement? {
        if (element is PsiFile) return null
        if (element.elementType == macroSignature) return element.containingFile
        val scope = PsiTreeUtil.findFirstParent(element, false) { it.elementType == macroDefinition }
            ?: element.containingFile
        val container = PsiTreeUtil.getChildOfType(scope, MipsDeclaration::class.java) ?: element.containingFile
        if (element.isSegment()) return container
        val boundaries = boundaries(scope)
        val positions = if (element.elementType == labelType) boundaries.segments else boundaries.statements
        return positions.floorEntry(element.textOffset)?.value ?: container
    }

    // These crumbs describe ranges between sibling statements, which the sticky-lines API
    // cannot represent using the selected declaration's PSI range.
    override fun acceptStickyElement(element: PsiElement): Boolean = false
}

private val macroDefinition = MipsTypes.psi("MACRO_DEFINITION")
private val macroSignature = MipsTypes.psi("MACRO_SIGNATURE")
private val statementType = MipsTypes.psi("STATEMENT")
private val labelType = MipsTypes.psi("LABEL")
private val directiveType = MipsTypes.psi("DIRECTIVE_STATEMENT")
private val segmentType = MipsTypes.psi("SEGMENT")

private fun PsiElement.isSegment(): Boolean = elementType == segmentType && parent.elementType == directiveType

private class Boundaries {
    val statements = TreeMap<Int, PsiElement>()
    val segments = TreeMap<Int, PsiElement>()
}

private fun boundaries(scope: PsiElement): Boundaries = CachedValuesManager.getCachedValue(scope) {
    val result = Boundaries()
    for (statement in scope.children) {
        ProgressManager.checkCanceled()
        if (statement.elementType != statementType) continue
        val label = statement.node.findChildByType(labelType)?.psi
        if (label != null) result.statements[label.textOffset] = label
        val segment = statement.node.findChildByType(directiveType)?.firstChildNode?.psi
        if (segment?.elementType == segmentType) {
            result.statements[segment.textOffset] = segment
            result.segments[segment.textOffset] = segment
        }
    }
    CachedValueProvider.Result.create(result, scope.containingFile)
}
