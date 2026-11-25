package edu.missouristate.mars.plugin.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import edu.missouristate.mars.api.SyscallEnvironment
import java.nio.file.Path

/**
 * Customizes the syscall environment of one in-process Run or Debug launch.
 *
 * Called on the execution worker before the first guest instruction, in extension order. The
 * returned environment is used on the simulator thread. Return `environment` unchanged for
 * unrelated launches. Create independent mutable state for every invocation; do not use
 * thread-local state or retain the project, environment or lifetime after the launch ends.
 *
 * Register owned resources with `lifetime` before doing work that can fail. MARS disposes them
 * after simulation stops (including cancellation or setup failure), before closing the default
 * environment. Never close the supplied environment. MARS retains its outer cancellation and IDE
 * dialog handling. Exceptions abort launch rather than silently executing with different I/O.
 * Customizers must not block waiting for IDE interaction.
 */
fun interface MarsSyscallEnvironmentCustomizer {
  fun customize(
      project: Project,
      entry: Path,
      environment: SyscallEnvironment,
      lifetime: Disposable,
  ): SyscallEnvironment

  companion object {
    val EP =
        ExtensionPointName<MarsSyscallEnvironmentCustomizer>(
            "edu.missouristate.mars.syscallEnvironmentCustomizer",
        )
  }
}

internal inline fun <T> withCustomizedSyscallEnvironment(
    project: Project,
    entry: Path,
    environment: SyscallEnvironment,
    action: (SyscallEnvironment) -> T,
): T {
  val lifetime = Disposer.newDisposable("MARS syscall environment")
  try {
    val customized =
        MarsSyscallEnvironmentCustomizer.EP.extensionList.fold(environment) { current, customizer ->
          customizer.customize(project, entry, current, lifetime)
        }
    return action(customized)
  } finally {
    Disposer.dispose(lifetime)
  }
}
