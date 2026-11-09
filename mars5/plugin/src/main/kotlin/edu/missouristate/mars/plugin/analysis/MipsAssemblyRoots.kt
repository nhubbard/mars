package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.include.FileIncludeManager
import edu.missouristate.mars.plugin.language.MipsFileType

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
                if (include.first.fileType == MipsFileType) add(include.first)
                true
            }
        }
        if (includers.isEmpty()) roots.add(file)
        for (includer in includers) if (visited.add(includer)) pending.addLast(includer)
    }
    return roots
}
