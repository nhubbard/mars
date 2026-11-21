package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CompletableFuture

/** Exercise initialization only: native Features Trainer owns the lesson UI and task lifecycle. */
internal object MarsLearningWorkspace {
    private const val EXAMPLE = "/learnProjects/mars_mips/MARSLearningProject/first-program.asm"

    fun ensureExample(project: Project): Path = ensureExampleAt(projectRoot(project))

    fun ensureExampleAt(root: Path): Path {
        var folder = root
        for (name in listOf("mars-labs", "console")) {
            folder = folder.resolve(name)
            try {
                Files.createDirectory(folder)
            } catch (_: FileAlreadyExistsException) {
                require(Files.isDirectory(folder, NOFOLLOW_LINKS)) { "Lesson folder is not a regular directory: $folder" }
            }
        }
        val file = folder.resolve("first-program.asm")
        try {
            val bytes = MarsLearningWorkspace::class.java.getResourceAsStream(EXAMPLE)!!.use { it.readBytes() }
            Files.write(file, bytes, CREATE_NEW, WRITE)
        } catch (_: FileAlreadyExistsException) {
            require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Lesson source is not a regular file: $file" }
        }
        return file
    }

    fun openExample(project: Project): CompletableFuture<Boolean> {
        val path = projectRoot(project).resolve("mars-labs/console/first-program.asm")
        return project.service<MarsLearningExecution>().openLessonFile(path)
    }

    private fun projectRoot(project: Project): Path = Path.of(
        requireNotNull(project.basePath) { "Open a project before starting a MARS lab" },
    )
}
