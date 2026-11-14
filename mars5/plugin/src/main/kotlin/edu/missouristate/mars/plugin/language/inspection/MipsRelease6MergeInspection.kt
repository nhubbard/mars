package edu.missouristate.mars.plugin.language.inspection

import com.intellij.DynamicBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.elementType
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.operationHead
import edu.missouristate.mars.plugin.language.operationOwners

internal class MipsRelease6MergeInspection : LocalInspectionTool() {
    override fun runForWholeFile() = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? MipsFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (file.project.service<MarsAssemblySettings>().target != MarsTarget.MARS64) return PsiElementVisitor.EMPTY_VISITOR
        val analysis = file.project.service<MarsAnalysisService>()
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.elementType !in operationOwners) return
                val site = analysis.operationSite(file, element.operationHead) ?: return
                if (site.type != TokenTypes.OPERATOR) return
                val replacement = when (site.name.lowercase()) {
                    "lwl", "lwr" -> "lw"
                    "swl", "swr" -> "sw"
                    else -> return
                }
                val token = file.findElementAt(site.range.startOffset) ?: return
                holder.registerProblem(token, messages.getMessage("inspection.release6.merge.removed", site.name, replacement))
            }
        }
    }

    private companion object {
        val messages = DynamicBundle(MipsRelease6MergeInspection::class.java, "messages.MarsBundle")
    }
}
