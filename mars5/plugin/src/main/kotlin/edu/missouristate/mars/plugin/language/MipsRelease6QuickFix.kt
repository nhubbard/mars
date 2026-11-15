package edu.missouristate.mars.plugin.language

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.Presentation
import com.intellij.modcommand.PsiBasedModCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.sourceLineStart
import edu.missouristate.mars.plugin.dynamicBundleOf
import edu.missouristate.mars.util.Binary

internal fun release6QuickFix(site: PsiElement): MipsRelease6QuickFix? {
    val name = site.text.lowercase()
    val replacement = when (name) {
        "daddi" -> "daddiu"
        in branchLikely -> name.dropLast(1)
        else -> return null
    }
    return MipsRelease6QuickFix(site, name, replacement).takeIf { it.isApplicable(site) }
}

/** Edits only the mnemonic when both spellings have the same effect for the written operands. */
internal class MipsRelease6QuickFix(
    site: PsiElement, private val mnemonic: String, private val replacement: String,
) : PsiBasedModCommandAction<PsiElement>(site) {
    override fun getFamilyName(): String = messages.getMessage("inspection.release6.fix", mnemonic, replacement)

    override fun getPresentation(context: ActionContext, element: PsiElement): Presentation? =
        Presentation.of(familyName).takeIf { isApplicable(element) }

    override fun perform(context: ActionContext, element: PsiElement): ModCommand {
        if (!isApplicable(element)) return ModCommand.nop()
        val range = element.textRange
        return ModCommand.psiUpdate(element.containingFile) { writable ->
            writable.viewProvider.document!!.replaceString(range.startOffset, range.endOffset, replacement)
        }
    }

    internal fun isApplicable(site: PsiElement): Boolean {
        if (!site.text.equals(mnemonic, ignoreCase = true)) return false
        val file = site.containingFile as MipsFile
        val analysis = file.project.service<MarsAnalysisService>()
        val prefix = analysis.editorTimeline(file).prefix(file, sourceLineStart(site))
        if (prefix.hasIncompleteIncludes || prefix.openMacro != null || prefix.equivalence(site.text) != null) return false
        val tokens = analysis.expandedInstruction(file, site) ?: return false
        if (tokens.isEmpty || !tokens[0].value.equals(mnemonic, ignoreCase = true)) return false
        if (mnemonic == "daddi") {
            if (tokens.size() != 4 || !TokenTypes.isIntegerTokenType(tokens[3].type)) return false
            val immediate = Binary.stringToInt(tokens[3].value)
            return immediate in Short.MIN_VALUE..Short.MAX_VALUE &&
                (immediate == 0 || RegisterNames.integer(tokens[2].value)?.number == 0)
        }

        // Annulment is immaterial when the delay slot is an actual no-op.
        val document = file.viewProvider.document!!
        val next = document.getLineNumber(site.textOffset) + 1
        if (next >= document.lineCount) return false
        val slot = document.charsSequence.subSequence(document.getLineStartOffset(next), document.getLineEndOffset(next))
        return slot.toString().substringBefore('#').trim().equals("nop", ignoreCase = true)
    }
}

private val branchLikely = setOf("beql", "bnel", "bgezl", "bgtzl", "blezl", "bltzl")
private val messages = dynamicBundleOf<MipsRelease6QuickFix>("messages.MarsBundle")
