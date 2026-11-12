package edu.missouristate.mars.plugin.language

import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiElement
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.ProcessingContext
import edu.missouristate.mars.plugin.language.psi.MipsMacroFormal
import edu.missouristate.mars.plugin.language.psi.checkedRenameSites
import edu.missouristate.mars.plugin.language.psi.formalRenameError
import edu.missouristate.mars.plugin.language.psi.isMacroFormalName

internal class MipsMacroFormalRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement) = element is MipsMacroFormal

    override fun renameElement(element: PsiElement, newName: String, usages: Array<UsageInfo>, listener: RefactoringElementListener?) {
        val sites = (element as MipsMacroFormal).checkedRenameSites(newName)
        val references = usages.mapNotNull { it.reference }.mapTo(HashSet()) { it.element }
        if (sites != references) throw formalRenameError("macro.rename.incomplete.usages")
        super.renameElement(element, newName, usages, listener)
    }
}

internal class MipsMacroFormalRenameInputValidator : RenameInputValidator {
    override fun getPattern(): PsiElementPattern.Capture<MipsMacroFormal?> = PlatformPatterns.psiElement(MipsMacroFormal::class.java)

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext) = isMacroFormalName(newName)
}
