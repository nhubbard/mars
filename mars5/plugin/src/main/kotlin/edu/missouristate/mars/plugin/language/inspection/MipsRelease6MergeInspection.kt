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
import edu.missouristate.mars.assembler.release6DivOperandMigration
import edu.missouristate.mars.assembler.release6Migration
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.operationHead
import edu.missouristate.mars.plugin.language.operationOwners

internal class MipsRelease6MergeInspection : LocalInspectionTool() {
    override fun runForWholeFile() = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // The background assembler already reports the same diagnostic in the editor.
        if (isOnTheFly) return PsiElementVisitor.EMPTY_VISITOR
        val file = holder.file as? MipsFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (file.project.service<MarsAssemblySettings>().target != MarsTarget.MARS64) return PsiElementVisitor.EMPTY_VISITOR
        val analysis = file.project.service<MarsAnalysisService>()
        val catalog = InstructionDefaults.forTarget(MarsTarget.MARS64)
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.elementType !in operationOwners) return
                val site = analysis.operationSite(file, element.operationHead, includeUnrecognized = true) ?: return
                if (site.macroDefinition != null || site.type !in instructionTypes) return
                val guidance = if (catalog.matchOperator(site.name) != null) {
                    if (site.name.lowercase() !in divides) return
                    val tokens = analysis.expandedInstruction(file, element.operationHead) ?: return
                    release6DivOperandMigration(tokens) ?: return
                } else {
                    val migration = release6Migration(site.name) ?: return
                    messages.getMessage("inspection.release6.merge.removed", site.name, migration)
                }
                // Operation sites come from this file's PSI during the same read action.
                val token = file.findElementAt(site.range.startOffset)!!
                holder.registerProblem(token, guidance)
            }
        }
    }

    private companion object {
        val messages = DynamicBundle(MipsRelease6MergeInspection::class.java, "messages.MarsBundle")
        val divides = setOf("div", "divu", "ddiv", "ddivu")
        val instructionTypes = setOf(TokenTypes.OPERATOR, TokenTypes.IDENTIFIER)
    }
}
