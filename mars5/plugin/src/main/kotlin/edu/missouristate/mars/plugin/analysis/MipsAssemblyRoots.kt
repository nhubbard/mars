package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.include.FileIncludeManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.index.resolveIndexedMipsInclude
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import java.nio.file.Path

private data class AssemblyAncestors(val roots: List<VirtualFile>, val withoutRoot: Set<VirtualFile>)

private fun assemblyAncestors(file: MipsFile): AssemblyAncestors =
    CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(
            assemblyAncestors(file.project, file.viewProvider.virtualFile),
            PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
        )
    }

internal fun inferredMipsAssemblyRoot(file: MipsFile): VirtualFile? =
    assemblyAncestors(file).roots.singleOrNull()

internal fun inferredMipsAssemblyEntry(file: MipsFile): Path? =
    inferredMipsAssemblyRoot(file)?.let { it.toNioPath().toAbsolutePath().normalize() }

/** In a graph without an entry, an outgoing edge to an ancestor closes a cycle. */
internal fun closesRootlessMipsIncludeCycle(file: MipsFile, literal: MipsStringLiteral, path: String): Boolean {
    val candidates = assemblyAncestors(file).withoutRoot
    if (candidates.isEmpty()) return false
    val target = resolveMipsInclude(literal, path) ?: return false
    return target.virtualFile in candidates
}

/** Finds top-level MIPS entries, including the file itself when it has no MIPS includers. */
internal fun mipsAssemblyRoots(project: Project, selected: VirtualFile): List<VirtualFile> =
    assemblyAncestors(project, selected).roots

private fun assemblyAncestors(project: Project, selected: VirtualFile): AssemblyAncestors {
    val manager = FileIncludeManager.getManager(project)
    val psi = PsiManager.getInstance(project)
    val visited = hashSetOf(selected)
    val pending = ArrayDeque(listOf(selected))
    val roots = ArrayList<VirtualFile>()
    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val file = pending.removeFirst()
        val context = psi.findFile(file) ?: continue
        // The cached getIncludingFiles result tracks existing parents only. Query the same
        // index directly so an edit in another file can introduce a new parent.
        val includers = buildSet {
            manager.processIncludingFiles(context) { include ->
                val candidate = include.first
                val info = include.second
                // The platform retries an unresolved provider result with generic path rules.
                // Accept only edges that the forward MARS include traversal can follow.
                if (candidate.fileType == MipsFileType &&
                    resolveIndexedMipsInclude(info, psi.findFile(candidate)!!)?.virtualFile == file
                ) add(candidate)
                true
            }
        }
        if (includers.isEmpty()) roots.add(file)
        for (includer in includers) if (visited.add(includer)) pending.addLast(includer)
    }
    return AssemblyAncestors(roots, if (roots.isEmpty()) visited else emptySet())
}
