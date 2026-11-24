package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.cli.grading.FunctionFileReadCheck
import edu.missouristate.mars.cli.grading.ReadOnlyFileEnvironment
import edu.missouristate.mars.plugin.run.MarsSyscallEnvironmentCustomizer
import java.nio.file.Path

/** Only immutable input definitions live with a Learn task; execution state belongs to launches. */
@Service(Service.Level.PROJECT)
internal class MarsLearningFileInputs : Disposable {
  private class Binding(val definition: FunctionFileReadCheck)

  private val bindings = mutableMapOf<Path, Binding>()

  fun bind(entry: Path, definition: FunctionFileReadCheck, lifetime: Disposable) {
    val path = entry.toAbsolutePath().normalize()
    val binding = Binding(definition)
    synchronized(this) { bindings[path] = binding }
    Disposer.register(lifetime) {
      synchronized(this) {
        if (bindings[path] === binding) bindings.remove(path)
      }
    }
  }

  @Synchronized
  fun definition(entry: Path): FunctionFileReadCheck? =
      bindings[entry.toAbsolutePath().normalize()]?.definition

  @Synchronized
  override fun dispose() {
    bindings.clear()
  }
}

/** MIPS file fixtures use MARS's public launch hook and the grader's virtual files. */
internal class MarsLearningFileEnvironmentCustomizer : MarsSyscallEnvironmentCustomizer {
  override fun customize(
      project: Project,
      entry: Path,
      environment: SyscallEnvironment,
      lifetime: Disposable,
  ): SyscallEnvironment {
    val definition =
        project.service<MarsLearningFileInputs>().definition(entry) ?: return environment
    val files = ReadOnlyFileEnvironment(environment, definition)
    Disposer.register(lifetime) { files.close() }
    return files
  }
}
