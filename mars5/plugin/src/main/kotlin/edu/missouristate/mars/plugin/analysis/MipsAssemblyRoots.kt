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
import edu.missouristate.mars.plugin.language.references.dotSegments
import java.nio.file.Path

internal fun inferredMipsAssemblyEntry(file: MipsFile): Path? =
    CachedValuesManager.getCachedValue(file) {
        val entry = mipsAssemblyRoots(file.project, file.viewProvider.virtualFile).singleOrNull()
            ?.let { it.toNioPath().toAbsolutePath().normalize() }
        CachedValueProvider.Result.create(
            entry, PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
        )
    }

/** Finds top-level MIPS entries, including the file itself when it has no MIPS includers. */
internal fun mipsAssemblyRoots(project: Project, selected: VirtualFile): List<VirtualFile> {
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
                if (candidate.fileType == MipsFileType) {
                    // IntelliJ retries unresolved provider results with its generic path rules.
                    // Recheck dot segments before admitting such an edge to MARS's graph.
                    if (info.path.split('/').none { it in dotSegments } ||
                        resolveIndexedMipsInclude(info, psi.findFile(candidate)!!)?.virtualFile == file
                    ) add(candidate)
                }
                true
            }
        }
        if (includers.isEmpty()) roots.add(file)
        for (includer in includers) if (visited.add(includer)) pending.addLast(includer)
    }
    return roots
}
