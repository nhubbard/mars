package edu.missouristate.mars.plugin.language.references

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import edu.missouristate.mars.plugin.analysis.assemblySourceOriginalFile
import edu.missouristate.mars.plugin.analysis.assemblySourceSyntax
import edu.missouristate.mars.plugin.analysis.inferredMipsAssemblyRoot
import edu.missouristate.mars.plugin.analysis.unresolvedAssemblySourceSyntax
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.rememberMipsAssemblyEntry
import edu.missouristate.mars.plugin.language.psi.mipsIncludeSites

/** Opens MIPS source when the platform cannot open an explicit include as text. */
internal class MipsIncludeNavigation : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val literal = sourceElement as? MipsStringLiteral ?: return null
        val source = literal.containingFile as MipsFile
        val path = mipsIncludeSites(source).paths[literal.textOffset] ?: return null
        val reference = MipsFileReferenceSet(path, literal).lastReference ?: return null
        val range = reference.rangeInElement.shiftRight(literal.textOffset)
        if (offset !in range.startOffset until range.endOffset) return null

        val request = source.assemblySourceOriginalFile().mipsIncludeRequest(path) ?: return null
        val file = VirtualFileManager.getInstance().findMipsIncludeFile(request) ?: return null
        // Keep the read-only view out of the reference graph so file operations target the physical file.
        val destination = when {
            reference.multiResolve(false).isEmpty() -> unresolvedAssemblySourceSyntax(source.project, file)
            file.fileType.isBinary -> assemblySourceSyntax(PsiManager.getInstance(source.project).findFile(file))
            else -> null
        } ?: return null
        if (!DumbService.isDumb(source.project)) {
            inferredMipsAssemblyRoot(source)?.let(destination.virtualFile::rememberMipsAssemblyEntry)
        }
        return arrayOf(destination)
    }
}
