package edu.missouristate.mars.plugin.run

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.plugin.MarsSessionService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsSyscallEnvironmentCustomizerPlatformTest {
  private val projectFixture = projectFixture(openAfterCreation = true)
  private val project
    get() = projectFixture.get()

  private val entry
    get() = Path.of(requireNotNull(project.basePath), "entry.asm")

  private inline fun <T> extensions(
      customizers: List<MarsSyscallEnvironmentCustomizer>,
      action: () -> T,
  ): T {
    val registration = Disposer.newDisposable()
    try {
      ExtensionTestUtil.maskExtensions(
          MarsSyscallEnvironmentCustomizer.EP,
          customizers,
          registration,
      )
      return action()
    } finally {
      Disposer.dispose(registration)
    }
  }

  private inline fun <T> withIo(
      action: (SessionSyscallEnvironment, ByteArrayOutputStream) -> T,
  ): T =
      MarsSessionService.createMarsComponent().context().use { session ->
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes).use { output ->
          SessionSyscallEnvironment(
                  session.memory,
                  ByteArrayInputStream("1\n".toByteArray()),
                  output,
                  output,
              )
              .use { io ->
                action(io, bytes)
              }
        }
      }

  @Test
  fun `no extensions preserves the exact default environment and console`() =
      extensions(emptyList()) {
        withIo { io, output ->
          withCustomizedSyscallEnvironment(project, entry, io) { actual ->
            assertSame(io, actual)
            assertEquals(1, actual.readInt())
            actual.printString("unchanged")
          }
          io.printString(" default remains owned by caller")
          assertEquals("unchanged default remains owned by caller", output.toString())
        }
      }

  @Test
  fun `extensions compose in platform order and dispose before the default owner closes`() {
    val callbacks = mutableListOf<String>()
    var firstLifetime: Disposable? = null
    val first = MarsSyscallEnvironmentCustomizer { actualProject, actualEntry, io, lifetime ->
      assertSame(project, actualProject)
      assertEquals(entry, actualEntry)
      firstLifetime = lifetime
      Disposer.register(lifetime) {
        io.printString("first")
        callbacks.add("first")
      }
      object : SyscallEnvironment by io {
        override fun readInt() = io.readInt() + 10
      }
    }
    val second = MarsSyscallEnvironmentCustomizer { _, _, io, lifetime ->
      assertSame(firstLifetime, lifetime)
      Disposer.register(lifetime) { callbacks.add("second") }
      object : SyscallEnvironment by io {
        override fun readInt() = io.readInt() * 2
      }
    }
    extensions(listOf(first, second)) {
      withIo { io, output ->
        val result =
            withCustomizedSyscallEnvironment(project, entry, io) { actual ->
              assertEquals(22, actual.readInt())
              assertTrue(callbacks.isEmpty())
              "result"
            }
        assertEquals("result", result)
        assertEquals(listOf("second", "first"), callbacks)
        assertEquals("first", output.toString())
      }
    }
  }

  @Test
  fun `simultaneous launches keep customized state and lifetimes independent`() =
      timeoutRunBlocking {
        val arrived = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val disposed = AtomicInteger()
        val customizer = MarsSyscallEnvironmentCustomizer { _, _, io, lifetime ->
          Disposer.register(lifetime) { disposed.incrementAndGet() }
          object : SyscallEnvironment by io {
            private var count = 0

            override fun readInt() = ++count
          }
        }
        extensions(listOf(customizer)) {
          val values =
              (1..2)
                  .map {
                    async(Dispatchers.Default) {
                      withIo { io, _ ->
                        withCustomizedSyscallEnvironment(project, entry, io) { actual ->
                          assertEquals(1, actual.readInt())
                          if (arrived.incrementAndGet() == 2) allStarted.complete(Unit)
                          allStarted.await()
                          actual.readInt()
                        }
                      }
                    }
                  }
                  .awaitAll()
          assertEquals(listOf(2, 2), values)
          assertEquals(2, disposed.get())
        }
      }

  @Test
  fun `setup failure disposes both earlier and failing resources without executing guest code`() {
    val disposed = mutableListOf<String>()
    val first = MarsSyscallEnvironmentCustomizer { _, _, io, lifetime ->
      Disposer.register(lifetime) { disposed.add("first") }
      io
    }
    val second = MarsSyscallEnvironmentCustomizer { _, _, _, lifetime ->
      Disposer.register(lifetime) { disposed.add("second") }
      error("setup failure")
    }
    extensions(listOf(first, second)) {
      withIo { io, _ ->
        val failure =
            assertThrows(IllegalStateException::class.java) {
              withCustomizedSyscallEnvironment(project, entry, io) {
                fail("Guest must not execute")
              }
            }
        assertEquals("setup failure", failure.message)
        assertEquals(listOf("second", "first"), disposed)
        io.printString("owner still usable")
      }
    }
  }

  @Test
  fun `execution failure disposes customization resources`() {
    var disposed = false
    val customizer = MarsSyscallEnvironmentCustomizer { _, _, io, lifetime ->
      Disposer.register(lifetime) { disposed = true }
      io
    }
    extensions(listOf(customizer)) {
      withIo { io, _ ->
        assertThrows(IllegalArgumentException::class.java) {
          withCustomizedSyscallEnvironment(project, entry, io) {
            throw IllegalArgumentException("guest failed")
          }
        }
        assertTrue(disposed)
      }
    }
  }

  @Test
  fun `coroutine cancellation disposes customization resources`() = timeoutRunBlocking {
    val started = CompletableDeferred<Unit>()
    val disposed = CompletableDeferred<Unit>()
    val customizer = MarsSyscallEnvironmentCustomizer { _, _, io, lifetime ->
      Disposer.register(lifetime) { disposed.complete(Unit) }
      io
    }
    extensions(listOf(customizer)) {
      val job =
          launch(Dispatchers.Default) {
            withIo { io, _ ->
              withCustomizedSyscallEnvironment(project, entry, io) {
                started.complete(Unit)
                awaitCancellation()
              }
            }
          }
      started.await()
      assertFalse(disposed.isCompleted)
      job.cancelAndJoin()
      disposed.await()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `Run pipeline keeps IDE cancellation outside the selected environment`(stop: Boolean) =
      timeoutRunBlocking {
        val selected = CompletableDeferred<Unit>()
        val disposed = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val customizer =
            MarsSyscallEnvironmentCustomizer { actualProject, actualEntry, io, lifetime ->
              assertSame(project, actualProject)
              assertEquals(entry, actualEntry)
              Disposer.register(lifetime) { disposed.complete(Unit) }
              selected.complete(Unit)
              object : SyscallEnvironment by io {
                override fun printInt(value: Int) {
                  calls.incrementAndGet()
                  io.printInt(value + 40)
                }

                override fun sleepMillis(milliseconds: Int) =
                    fail<Unit>("IDE must retain cancellable sleep")
              }
            }
        val source =
            if (stop) $$"li $a0, 600000\nli $v0, 32\nsyscall\n"
            else $$"li $a0, 2\nli $v0, 1\nsyscall\nli $v0, 10\nsyscall\n"
        withContext(Dispatchers.IO) {
          Files.writeString(entry, ".text\n$source")
          assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry))
        }
        extensions(listOf(customizer)) {
          val exit = CompletableDeferred<Int>()
          val output = StringBuilder()
          val handler =
              project.service<MarsRunService>().createProcess(entry, memoryDumps = emptyList())
          handler.addProcessListener(
              object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                  if (outputType == ProcessOutputTypes.STDOUT) output.append(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                  exit.complete(event.exitCode)
                }
              },
          )
          try {
            withContext(Dispatchers.EDT) { handler.startNotify() }
            selected.await()
            if (stop) withContext(Dispatchers.EDT) { handler.destroyProcess() }
            assertEquals(if (stop) 130 else 0, exit.await())
            disposed.await()
            assertEquals(if (stop) "" else "42", output.toString())
            assertEquals(if (stop) 0 else 1, calls.get())
          } finally {
            if (!handler.isProcessTerminated)
                withContext(Dispatchers.EDT) { handler.destroyProcess() }
          }
        }
      }
}
