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
import edu.missouristate.mars.plugin.language.references.dotSegments
import edu.missouristate.mars.plugin.language.references.findMipsIncludeFile
import edu.missouristate.mars.plugin.language.references.mipsIncludeRequest
import java.nio.file.Path

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
        val request = source.mipsIncludeRequest(path)
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
        val missing = ArrayDeque<String>()
        val ancestors = generateSequence(request.parent, Path::getParent)
        val ancestor = ancestors.map { it to files.findMipsIncludeFile(it) }
            // A missing directory before a dot segment must be resolved before creating files.
            .takeWhile { (path, file) -> file != null || path.fileName.toString() !in dotSegments }
            .mapNotNull { (path, file) ->
                if (file == null) missing.addFirst(path.fileName.toString())
                file
            }
            .firstOrNull()
        val directory = ancestor?.let(literal.manager::findDirectory) ?: return null
        val name = request.fileName.toString()
        try {
            if (missing.isEmpty()) directory.checkCreateFile(name) else directory.checkCreateSubdirectory(missing.first())
        } catch (_: IncorrectOperationException) {
            return null
        }
        // The native action accepts a relative path and creates it in one command. Including
        // the suffix here also keeps its availability check from using the basename at the ancestor.
        val relativePath = (missing + name).joinToString("/")
        return CreateFilePathFix(literal, NewFileLocation(listOf(TargetDirectory(directory)), relativePath))
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
