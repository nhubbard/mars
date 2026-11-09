package edu.missouristate.mars.plugin

import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.project.Project
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal fun projectFilePath(project: Project, value: String?): Path? {
    val expanded = PathMacroManager.getInstance(project).expandPath(value) ?: return null
    return try {
        Path.of(expanded).normalize().takeIf { it.isAbsolute && it.fileName != null }
    } catch (_: InvalidPathException) {
        null
    }
}
