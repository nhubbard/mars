package edu.missouristate.mars.plugin.build

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider
import edu.missouristate.mars.plugin.projectAbsolutePath
import edu.missouristate.mars.plugin.run.MarsRunConfiguration
import edu.missouristate.mars.plugin.run.MarsRunType
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/** Keeps configured inputs attached to files when IntelliJ refactors their paths. */
internal class MarsSourceRefactoringListener : RefactoringElementListenerProvider {
    override fun getListener(element: PsiElement): RefactoringElementListener? {
        val item = element as? PsiFileSystemItem ?: return null
        val file = item.virtualFile ?: return null
        if (!file.isInLocalFileSystem) return null
        val oldPath = file.toNioPath()
        val directory = file.isDirectory
        val project = element.project
        return object : RefactoringElementListener {
            override fun elementMoved(newElement: PsiElement) = updatePaths(newElement)
            override fun elementRenamed(newElement: PsiElement) = updatePaths(newElement)

            private fun updatePaths(newElement: PsiElement) {
                val newPath = (newElement as PsiFileSystemItem).virtualFile.toNioPath()
                relocateInputs(project, oldPath, newPath, directory)
            }
        }
    }
}

private data class RunPaths(val source: String?, val directory: String?)

private data class RunPathChange(val run: MarsRunConfiguration, val before: RunPaths, val after: RunPaths) {
    fun replace(from: RunPaths, to: RunPaths) {
        with(run.options) {
            if (sourcePath == from.source) sourcePath = to.source
            if (workingDirectory == from.directory) workingDirectory = to.directory
        }
    }
}

private fun relocateInputs(project: Project, oldPath: Path, newPath: Path, directory: Boolean) {
    val macros = PathMacroManager.getInstance(project)
    fun relocated(stored: String): String {
        val path = projectAbsolutePath(project, stored) ?: return stored
        if (path != oldPath && !(directory && path.startsWith(oldPath))) return stored
        return macros.collapsePath(newPath.resolve(oldPath.relativize(path)).invariantSeparatorsPathString)!!
    }

    val assembly = project.service<MarsAssemblySettings>()
    val before = assembly.sourcePaths
    val after = MarsAssemblySettings.SourcePaths(before.sources.map(::relocated), before.handler?.let(::relocated))
    val type = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java)
    val runs = RunManager.getInstance(project).getConfigurationsList(type).mapNotNull { config ->
        val run = config as MarsRunConfiguration
        val original = RunPaths(run.options.sourcePath, run.options.workingDirectory)
        val moved = RunPaths(original.source?.let(::relocated), original.directory?.let(::relocated))
        if (original == moved) null else RunPathChange(run, original, moved)
    }
    if (before == after && runs.isEmpty()) return
    val update = object : BasicUndoableAction() {
        override fun undo() {
            assembly.replaceSourcePaths(after, before)
            runs.forEach { it.replace(it.after, it.before) }
        }

        override fun redo() {
            assembly.replaceSourcePaths(before, after)
            runs.forEach { it.replace(it.before, it.after) }
        }
    }
    update.redo()
    UndoManager.getInstance(project).undoableActionPerformed(update)
}
