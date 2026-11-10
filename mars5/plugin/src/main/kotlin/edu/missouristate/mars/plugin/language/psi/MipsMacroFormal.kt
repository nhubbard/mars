package edu.missouristate.mars.plugin.language.psi

import com.intellij.DynamicBundle
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.util.IncorrectOperationException
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.MipsFile

internal class MipsMacroFormal(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement = firstChild
    override fun getName(): String = nameIdentifier.text

    override fun setName(name: String): PsiElement =
        throw IncorrectOperationException(bundle.getMessage("symbol.rename.unavailable"))

    fun effectiveNames(): Set<String> {
        val file = containingFile as MipsFile
        return file.project.service<MarsAnalysisService>().macroFormalNames(file, this)
    }

    private companion object {
        val bundle = DynamicBundle(MipsMacroFormal::class.java, "messages.MarsBundle")
    }
}
