package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.actions.ReaderModeMatcher
import com.intellij.codeInsight.actions.ReaderModeProvider.ReaderMode
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.FileContentUtilCore
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private val assemblySourceOrigin = Key.create<VirtualFile>("mars.assembly.source.origin")
private val assemblySourceEntries = Key.create<MutableSet<VirtualFile>>("mars.assembly.source.entries")
private val assemblySourceViews = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<VirtualFile, Boolean>()))

internal val VirtualFile.mipsAssemblySourceOrigin: VirtualFile?
    get() = getUserData(assemblySourceOrigin)

internal val VirtualFile.mipsAssemblySourceEntries: Set<VirtualFile>
    get() = getUserData(assemblySourceEntries).orEmpty()

internal fun VirtualFile.rememberMipsAssemblyEntry(entry: VirtualFile) {
    putUserDataIfAbsent(assemblySourceEntries, ConcurrentHashMap.newKeySet()).add(entry)
}

internal fun mipsAssemblySourceView(origin: VirtualFile, text: String): VirtualFile =
    LightVirtualFile.builder()
        .name(origin.name)
        .fileType(PlainTextFileType.INSTANCE)
        .content(text)
        // Closed-tab history can keep this file after unload. A Throwable backtrace would keep plugin classes.
        .creationTrace("MARS read-only assembly source")
        .build().apply {
            putUserData(assemblySourceOrigin, origin)
            isWritable = false
            assemblySourceViews.add(this)
        }

internal fun detachMipsAssemblySourceViews() {
    val files = synchronized(assemblySourceViews) {
        assemblySourceViews.toList().also { assemblySourceViews.clear() }
    }
    for (file in files) {
        file.putUserData(assemblySourceOrigin, null)
        file.putUserData(assemblySourceEntries, null)
    }
    // Closed light files retain their last PSI provider in the file's user data.
    FileContentUtilCore.reparseFiles(files)
}

/** Editor history can retain a closed view, so its file stores only platform objects. */
internal class MipsAssemblySourceFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile) = MipsFileType.takeIf { file.mipsAssemblySourceOrigin != null }
}

/** Generated includes are project sources even though their editor view is read-only. */
internal class MipsAssemblySourceReaderModeMatcher : ReaderModeMatcher {
    override fun matches(project: Project, file: VirtualFile, editor: Editor?, mode: ReaderMode): Boolean? =
        if (file.mipsAssemblySourceOrigin != null) false else null
}
