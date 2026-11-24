package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.cli.grading.FunctionFileReadCheck
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.run.MarsSyscallEnvironmentCustomizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MarsLearningFileInputsPlatformTest {
  private val projects = projectFixture(openAfterCreation = true)
  private val project
    get() = projects.get()

  private val entry
    get() = Path.of(requireNotNull(project.basePath), "entry.asm")

  private val definition =
      FunctionFileReadCheck("input.bin", "0080ff", 7, maxReadSize = 1, firstDescriptor = 19)

  @Test
  fun `replacement bindings survive old task disposal and paths normalize without affecting unrelated sources`() {
    val inputs = project.service<MarsLearningFileInputs>()
    val old = Disposer.newDisposable()
    val current = Disposer.newDisposable()
    try {
      inputs.bind(entry, definition, old)
      assertSame(definition, inputs.definition(entry.parent.resolve("sub/../entry.asm")))
      assertNull(inputs.definition(entry.resolveSibling("other.asm")))
      val replacement = definition.copy(bytes = "ff")
      inputs.bind(entry, replacement, current)
      Disposer.dispose(old)
      assertSame(replacement, inputs.definition(entry))
      Disposer.dispose(current)
      assertNull(inputs.definition(entry))
    } finally {
      Disposer.dispose(old)
      Disposer.dispose(current)
    }
  }

  @Test
  fun `registered customizer isolates launches and a paused launch outlives the completed Learn task`() {
    val customizer =
        MarsSyscallEnvironmentCustomizer.EP.extensionList
            .filterIsInstance<MarsLearningFileEnvironmentCustomizer>()
            .single()
    val task = Disposer.newDisposable()
    val firstLaunch = Disposer.newDisposable()
    val secondLaunch = Disposer.newDisposable()
    MarsSessionService.createMarsComponent().context().use { context ->
      PrintStream(ByteArrayOutputStream()).use { out ->
        SessionSyscallEnvironment(context.memory, ByteArrayInputStream(byteArrayOf()), out, out)
            .use { io ->
              try {
                assertSame(io, customizer.customize(project, entry, io, firstLaunch))
                project.service<MarsLearningFileInputs>().bind(entry, definition, task)
                assertSame(
                    io,
                    customizer.customize(
                        project,
                        entry.resolveSibling("other.asm"),
                        io,
                        firstLaunch,
                    ),
                )
                val first = customizer.customize(project, entry, io, firstLaunch)
                val second = customizer.customize(project, entry, io, secondLaunch)
                assertNotSame(first, second)
                assertEquals(19, first.openFile("input.bin", 0))
                assertEquals(19, second.openFile("input.bin", 0))
                val buffer = ByteArray(3)
                assertEquals(1, first.readFromFile(19, buffer, 3))
                assertEquals(0, buffer[0].toInt())
                Disposer.dispose(task) // Learn advances while the guest is still paused.
                assertSame(io, customizer.customize(project, entry, io, secondLaunch))
                assertEquals(1, first.readFromFile(19, buffer, 3))
                assertEquals(-128, buffer[0].toInt())
                Disposer.dispose(firstLaunch)
                assertThrows<IllegalStateException> { first.openFile("input.bin", 0) }
                assertEquals(1, second.readFromFile(19, buffer, 3))
                assertEquals(0, buffer[0].toInt())
                second.closeFile(19)
                io.printString("The frontend still owns its console.")
              } finally {
                Disposer.dispose(task)
                Disposer.dispose(firstLaunch)
                Disposer.dispose(secondLaunch)
              }
            }
      }
    }
  }
}
