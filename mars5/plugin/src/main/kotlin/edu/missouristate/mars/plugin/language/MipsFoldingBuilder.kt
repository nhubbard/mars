package edu.missouristate.mars.plugin.language

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiModificationTracker
import edu.missouristate.mars.plugin.analysis.MacroBody
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration

internal class MipsFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (root !is MipsFile) return FoldingDescriptor.EMPTY_ARRAY
        root.project.service<MipsFoldingRefresh>()
        val bodies = if (quick || DumbService.isDumb(root.project)) {
            writtenBodies(root)
        } else {
            root.project.service<MarsAnalysisService>().editorTimeline(root).localMacroBodies(root).asSequence()
        }
        val text = document.charsSequence
        val descriptors = ArrayList<FoldingDescriptor>()
        for ((opening, closingOffset) in bodies) {
            ProgressManager.checkCanceled()
            val firstLine = document.getLineNumber(opening.textOffset)
            val closingLine = document.getLineNumber(closingOffset)
            if (closingLine <= firstLine + 1) continue
            val start = document.getLineEndOffset(firstLine)
            val end = document.getLineEndOffset(closingLine - 1)
            if (text.subSequence(start, end).isNotBlank()) {
                val anchor = if (opening is MipsDeclaration) opening.parent else opening
                descriptors.add(FoldingDescriptor(anchor.node, TextRange(start, end), null, foldingDependencies))
            }
        }
        return descriptors.toTypedArray()
    }

    private fun writtenBodies(file: MipsFile): Sequence<MacroBody> = sequence {
        for (statement in file.node.getChildren(statements)) {
            ProgressManager.checkCanceled()
            val macro = statement.findChildByType(macroDefinition) ?: continue
            val closing = macro.findChildByType(endMacro) ?: continue
            yield(MacroBody(macro.psi, closing.startOffset))
        }
    }

    override fun getPlaceholderText(node: ASTNode) = " …"
    override fun isCollapsedByDefault(node: ASTNode) = false

    private companion object {
        val statements = TokenSet.create(MipsTypes.psi("STATEMENT"))
        val macroDefinition = MipsTypes.psi("MACRO_DEFINITION")
        val endMacro = MipsTypes.psi("END_MACRO")
        val foldingDependencies = setOf(PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
    }
}
