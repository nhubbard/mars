package edu.missouristate.mars.plugin.language.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.components.service
import com.intellij.psi.PsiCheckedRenameElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.MipsFile

internal class MipsMacroFormal(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner, PsiCheckedRenameElement {
    override fun getNameIdentifier(): PsiElement = firstChild
    override fun getName(): String = nameIdentifier.text

    override fun checkSetName(name: String) {
        checkedRenameSites(name)
    }

    override fun setName(name: String): PsiElement {
        nameIdentifier.replace(macroFormalNameElement(project, name))
        return this
    }

    fun effectiveNames(): Set<String> {
        val file = containingFile as MipsFile
        return file.project.service<MarsAnalysisService>().macroFormalNames(file, this)
    }
}
