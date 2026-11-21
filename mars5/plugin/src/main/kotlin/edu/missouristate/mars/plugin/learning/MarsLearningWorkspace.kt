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
    internal enum class Exercise(val fileName: String, val resourceName: String) {
        EXAMPLE("first-program.asm", "first-program.asm"),
        DIAGNOSIS("wrong-output.asm", "wrong-output.asm"),
        REPAIR("repair-output.asm", "wrong-output.asm"),
        INDEPENDENT("independent.asm", "independent.asm"),
    }

    fun ensureExample(project: Project, exercise: Exercise = Exercise.EXAMPLE): Path = ensureExampleAt(projectRoot(project), exercise)

    fun ensureExampleAt(root: Path, exercise: Exercise = Exercise.EXAMPLE): Path {
        var folder = root
        for (name in listOf("mars-labs", "console")) {
            folder = folder.resolve(name)
            try {
                Files.createDirectory(folder)
            } catch (_: FileAlreadyExistsException) {
                require(Files.isDirectory(folder, NOFOLLOW_LINKS)) { "Lesson folder is not a regular directory: $folder" }
            }
        }
        val file = folder.resolve(exercise.fileName)
        try {
            val bytes = MarsLearningWorkspace::class.java.getResourceAsStream("/learnProjects/mars_mips/MARSLearningProject/${exercise.resourceName}")!!.use { it.readBytes() }
            Files.write(file, bytes, CREATE_NEW, WRITE)
        } catch (_: FileAlreadyExistsException) {
            require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Lesson source is not a regular file: $file" }
        }
        return file
    }

    fun openExample(project: Project, exercise: Exercise = Exercise.EXAMPLE): CompletableFuture<Boolean> {
        val path = projectRoot(project).resolve("mars-labs/console").resolve(exercise.fileName)
        return project.service<MarsLearningExecution>().openLessonFile(path)
    }

    private fun projectRoot(project: Project): Path = Path.of(
        requireNotNull(project.basePath) { "Open a project before starting a MARS lab" },
    )
}
