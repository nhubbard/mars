package edu.missouristate.mars.plugin.language.references

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import edu.missouristate.mars.plugin.analysis.assemblySourceOriginalFile
import edu.missouristate.mars.plugin.analysis.unresolvedAssemblySourceSyntax
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.psi.mipsIncludeSites

/** Opens assembler source when the platform has no PSI target for an explicit include. */
internal class MipsIncludeNavigation : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val literal = sourceElement as? MipsStringLiteral ?: return null
        val source = literal.containingFile
        val path = mipsIncludeSites(source).paths[literal.textOffset] ?: return null
        val reference = MipsFileReferenceSet(path, literal).lastReference ?: return null
        val range = reference.rangeInElement.shiftRight(literal.textOffset)
        if (offset !in range.startOffset until range.endOffset || reference.multiResolve(false).isNotEmpty()) return null

        // Keep the captured view out of the file reference graph, where it would become a rename target.
        val request = source.assemblySourceOriginalFile().mipsIncludeRequest(path) ?: return null
        val file = VirtualFileManager.getInstance().findMipsIncludeFile(request) ?: return null
        val destination = unresolvedAssemblySourceSyntax(source.project, file) ?: return null
        return arrayOf(destination)
    }
}
