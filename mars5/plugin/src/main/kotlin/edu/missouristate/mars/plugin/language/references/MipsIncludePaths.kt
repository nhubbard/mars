package edu.missouristate.mars.plugin.language.references

import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path
import java.util.Objects.requireNonNullElse

/** Resolves an absolute VFS path in chunks, applying disk semantics at explicit dot segments. */
internal fun VirtualFileManager.findMipsIncludeFile(request: Path): VirtualFile? {
    if (request.none { it.toString() in dotSegments }) return findFileByNioPath(request)

    var prefix = request.root
    for (part in request) {
        val segment = part.toString()
        if (segment in dotSegments) {
            val file = findFileByNioPath(prefix) ?: return null
            val directory = file.resolveMipsDotSegment(segment) ?: return null
            prefix = directory.toNioPath()
        } else {
            prefix = prefix.resolve(part)
        }
    }
    return findFileByNioPath(prefix)
}

internal val dotSegments = setOf(".", "..")

/** Resolve directory aliases while keeping the written file name for relative includes. */
internal fun VirtualFile.mipsIncludeDirectoryPath(): String? =
    parent?.let { requireNonNullElse(it.canonicalPath, it.path) }

/** A dot segment requires a directory. At the filesystem root, the parent is the root itself. */
internal fun VirtualFile.resolveMipsDotSegment(segment: String): VirtualFile? {
    if (!isDirectory) return null
    if (segment == ".") return this
    val directory = if (`is`(VFileProperty.SYMLINK)) canonicalFile ?: return null else this
    return directory.parent ?: directory
}
