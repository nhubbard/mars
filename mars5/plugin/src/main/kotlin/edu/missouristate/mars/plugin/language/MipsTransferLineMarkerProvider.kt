package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import edu.missouristate.mars.assembler.OperandFormat
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.J_FORMAT
import edu.missouristate.mars.mips.instructions.ExtendedInstruction
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.discardTrailingComment
import edu.missouristate.mars.plugin.analysis.sourceLine
import edu.missouristate.mars.plugin.language.psi.MipsSymbolToken

internal class MipsTransferLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun getName(): String = bundle.getMessage("gutter.transfer.name")
    override fun getIcon() = AllIcons.Actions.Forward

    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        val head = element as? MipsSymbolToken ?: return
        val transfer = transferAt(head) ?: return
        if (transfer.targets.isEmpty()) return
        val pointer = SmartPointerManager.createPointer(head)
        result.add(NavigationGutterIconBuilder.create(icon, name)
            .setTooltipText(bundle.getMessage("gutter.transfer.tooltip", transfer.label))
            .setPopupTitle(bundle.getMessage("gutter.transfer.name"))
            .setEmptyPopupText(bundle.getMessage("gutter.transfer.unavailable"))
            .setTargets(NotNullLazyValue.lazy { pointer.element?.let(::transferAt)?.targets.orEmpty() })
            .createLineMarkerInfo(head))
    }

    private fun transferAt(head: MipsSymbolToken): Transfer? {
        val owner = operationOwner(head) ?: return null
        if (owner.operationHead !== head) return null
        val file = head.containingFile as MipsFile
        if (DumbService.isDumb(file.project)) return null
        val analysis = file.project.service<MarsAnalysisService>()
        val operation = analysis.operationSite(file, head) ?: return null
        if (operation.type != TokenTypes.OPERATOR) return null
        val candidates = transferForms[operation.name.lowercase()] ?: return null
        val tokens = analysis.editorInstructionTokens(file, head, sourceLine(head)) ?: return null
        tokens.discardTrailingComment()
        if (candidates.none { OperandFormat.matches(tokens, it) }) return null
        val label = tokens[tokens.size() - 1].value
        return Transfer(label, analysis.labelTargets(file, head, allowExternal = false, name = label))
    }

    private data class Transfer(val label: String, val targets: List<PsiElement>)

    private companion object {
        val bundle = DynamicBundle(MipsTransferLineMarkerProvider::class.java, "messages.MarsBundle")
        val transferForms by lazy {
            val forms = InstructionDefaults.get().instructionList
            val basic = forms.filterIsInstance<BasicInstruction>().filter {
                it.instructionFormat == I_BRANCH_FORMAT || it.instructionFormat == J_FORMAT
            }
            val pseudo = forms.filterIsInstance<ExtendedInstruction>().filter(ExtendedInstruction::branchesToLabel)
            (basic + pseudo).groupBy(Instruction::getName)
        }
    }
}
