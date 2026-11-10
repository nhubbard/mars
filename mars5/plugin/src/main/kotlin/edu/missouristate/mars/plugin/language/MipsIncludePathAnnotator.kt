package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.codeInsight.daemon.quickFix.CreateFilePathFix
import com.intellij.codeInsight.daemon.quickFix.NewFileLocation
import com.intellij.codeInsight.daemon.quickFix.TargetDirectory
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.util.IncorrectOperationException
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.psi.mipsIncludeSites
import edu.missouristate.mars.plugin.language.references.findMipsIncludeFile
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Objects.requireNonNullElse

/** The include prepass examines the physical line before resolving symbols or choosing a build entry. */
internal class MipsIncludePathAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.isUnterminatedInclude()) {
            holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.include.unterminated"))
                .range(element.textRange)
                .withFix(CloseIncludeQuote)
                .create()
            return
        }
        val literal = element as? MipsStringLiteral ?: return
        val file = literal.containingFile
        val path = mipsIncludeSites(file).paths[literal.textOffset] ?: return
        val source = file.originalFile.viewProvider.virtualFile
        if (!source.isInLocalFileSystem) return
        val request = try {
            source.toNioPath().resolveSibling(path)
        } catch (_: InvalidPathException) {
            null
        }
        val files = VirtualFileManager.getInstance()
        val target = request?.let(files::findMipsIncludeFile)
        if (target != null && !target.isDirectory) return
        val annotation = holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.include.missing", path))
            .range(literal.textRange)
        if (request != null && target == null) {
            val fix = createIncludeFix(literal, request, files)
            if (fix != null) annotation.withFix(fix)
        }
        annotation.create()
    }

    private fun createIncludeFix(literal: MipsStringLiteral, request: Path, files: VirtualFileManager): CreateFilePathFix? {
        val parentPath = requireNonNullElse(request.parent, request)
        val parent = files.findMipsIncludeFile(parentPath) ?: return null
        val directory = literal.manager.findDirectory(parent) ?: return null
        val name = request.fileName.toString()
        try {
            directory.checkCreateFile(name)
        } catch (_: IncorrectOperationException) {
            return null
        }
        return CreateFilePathFix(literal, NewFileLocation(listOf(TargetDirectory(directory)), name))
    }
}

private object CloseIncludeQuote : PsiUpdateModCommandAction<PsiElement>(PsiElement::class.java), DumbAware {
    override fun getFamilyName(): String = bundle.getMessage("syntax.include.closeQuote")

    override fun isElementApplicable(element: PsiElement, context: ActionContext): Boolean =
        element.isUnterminatedInclude()

    override fun invoke(context: ActionContext, element: PsiElement, updater: ModPsiUpdater) {
        updater.document.insertString(element.textRange.endOffset, "\"")
    }
}

private val unclosedString = MipsTypes.psi("UNCLOSED_STRING")
private val bundle = DynamicBundle(MipsIncludePathAnnotator::class.java, "messages.MarsBundle")

internal fun PsiElement.isUnterminatedInclude(): Boolean =
    elementType == unclosedString && textOffset in mipsIncludeSites(containingFile).unterminated
