package edu.missouristate.mars.plugin.language.inspection

import com.intellij.DynamicBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.elementType
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.operationHead
import edu.missouristate.mars.plugin.language.operationOwners

internal class MipsMacroArityInspection : LocalInspectionTool() {
    // A change to an earlier macro or equivalence can affect calls anywhere below it.
    override fun runForWholeFile() = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as MipsFile
        val analysis = file.project.service<MarsAnalysisService>()
        val timeline = analysis.editorTimeline(file)
        if (timeline.macroNames.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.elementType != statement) return
                val head = element.node.findChildByType(operationOwners)?.psi?.operationHead ?: run {
                    val leadingLabel = element.node.findChildByType(label) ?: return
                    leadingLabel.firstChildNode.psi
                }
                val mismatch = analysis.macroArityMismatch(file, timeline, head) ?: return
                holder.registerProblem(
                    mismatch.site,
                    messages.getMessage("inspection.macro.wrongArity", mismatch.name, mismatch.expected.joinToString(", "), mismatch.actual),
                )
            }
        }
    }

    private companion object {
        val messages = DynamicBundle(MipsMacroArityInspection::class.java, "messages.MarsBundle")
        val statement = MipsTypes.psi("STATEMENT")
        val label = MipsTypes.psi("LABEL")
    }
}
