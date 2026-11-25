package edu.missouristate.mars.plugin.platform

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.DocumentUtil
import edu.missouristate.mars.plugin.debug.debugSessionListener
import edu.missouristate.mars.plugin.editor.documentListener
import edu.missouristate.mars.plugin.editor.onDocumentChanged
import edu.missouristate.mars.plugin.run.MarsRunType
import edu.missouristate.mars.plugin.run.executionListener
import edu.missouristate.mars.plugin.run.processListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class ListenerFactoriesPlatformTest {
  private val projects = codeInsightProjectFixture()

  @Test
  fun `execution failure defaults preserve native forwarding and configured overload selection`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val project = projects.get()
          val settings =
              RunManager.getInstance(project)
                  .createConfiguration(
                      "listener",
                      runConfigurationType<MarsRunType>().configurationFactories.single(),
                  )
          val env =
              ExecutionEnvironmentBuilder.create(
                      DefaultRunExecutor.getRunExecutorInstance(),
                      settings,
                  )
                  .build()
          val handler = NopProcessHandler()
          val calls = mutableListOf<String>()
          val cause = IllegalStateException("failed")
          val defaults = executionListener {
            processNotStarted { id, actual ->
              assertSame(env, actual)
              calls += id
            }
          }
          defaults.processNotStarted("with cause", env, cause)
          defaults.processNotStarted("without cause", env)
          defaults.processStartScheduled("run", env)
          defaults.processStarting("run", env)
          defaults.processStarting("run", env, handler)
          defaults.processStarted("run", env, handler)
          defaults.processTerminating("run", env, handler)
          defaults.processTerminated("run", env, handler, 0)
          val empty = executionListener {}
          empty.processNotStarted("empty", env)
          empty.processNotStarted("null cause", env, null)
          val configured = executionListener {
            processStartScheduled { _, _ -> calls += "scheduled" }
            processStarting { _, _ -> calls += "starting" }
            processStarting { _, _, actual ->
              assertSame(handler, actual)
              calls += "handler"
            }
            processStarted { _, _, _ -> calls += "started" }
            processNotStarted { _, _ -> calls += "unexpected two-argument callback" }
            processNotStarted { _, actual, failure ->
              assertSame(env, actual)
              assertSame(cause, failure)
              calls += "cause"
            }
            processTerminating { _, _, actual ->
              assertSame(handler, actual)
              calls += "terminating"
            }
            processTerminated { _, _, _, code -> calls += "exit $code" }
          }
          configured.processStartScheduled("run", env)
          configured.processStarting("run", env)
          configured.processStarting("run", env, handler)
          configured.processStarted("run", env, handler)
          configured.processNotStarted("run", env, cause)
          configured.processTerminating("run", env, handler)
          configured.processTerminated("run", env, handler, 17)
          assertEquals(
              listOf(
                  "with cause",
                  "without cause",
                  "scheduled",
                  "starting",
                  "handler",
                  "started",
                  "cause",
                  "terminating",
                  "exit 17",
              ),
              calls,
          )
        }
      }

  @Test
  @Suppress(
      "UnstableApiUsage",
  ) // Public boolean overload forwards to the no-argument callback on the pinned IDE.
  fun `debug callbacks retain native frame forwarding and all defaults`() {
    val calls = mutableListOf<String>()
    val listener = debugSessionListener {
      sessionPaused { calls += "paused" }
      beforeSessionResume { calls += "before" }
      sessionResumed { calls += "resumed" }
      sessionStopped { calls += "stopped" }
      stackFrameChanged { calls += "frame" }
      settingsChanged { calls += "settings" }
      breakpointsMuted { calls += "muted $it" }
    }
    listener.sessionPaused()
    listener.beforeSessionResume()
    listener.sessionResumed()
    listener.stackFrameChanged(true)
    listener.settingsChanged()
    listener.breakpointsMuted(false)
    listener.sessionStopped()
    assertEquals(
        listOf("paused", "before", "resumed", "frame", "settings", "muted false", "stopped"),
        calls,
    )
    val empty = debugSessionListener {}
    empty.sessionPaused()
    empty.beforeSessionResume()
    empty.sessionResumed()
    empty.sessionStopped()
    empty.stackFrameChanged(false)
    empty.settingsChanged()
    empty.breakpointsMuted(true)
    assertNotSame(listener, empty)
  }

  @Test
  fun `process listeners keep receiver identity removal disposal and callback arguments`() {
    val handler = NopProcessHandler()
    val parent = Disposer.newDisposable()
    val calls = mutableListOf<String>()
    val listener = processListener {
      startNotified { calls += "start" }
      onTextAvailable { event, key ->
        assertSame(handler, event.processHandler)
        assertSame(ProcessOutputTypes.STDOUT, key)
        calls += requireNotNull(event.text)
        handler.removeProcessListener(this)
      }
    }
    try {
      handler.addProcessListener(listener, parent)
      handler.startNotify()
      handler.notifyTextAvailable("first", ProcessOutputTypes.STDOUT)
      handler.notifyTextAvailable("second", ProcessOutputTypes.STDOUT)
      assertEquals(listOf("start", "first"), calls)
      val event = ProcessEvent(handler, 7)
      val all = processListener {
        processTerminated {
          assertSame(event, it)
          calls += "terminated"
        }
        processWillTerminate { actual, destroyed ->
          assertSame(event, actual)
          calls += "destroyed $destroyed"
        }
        processNotStarted { calls += "not started" }
      }
      all.processWillTerminate(event, true)
      all.processTerminated(event)
      all.processNotStarted()
      all.startNotified(event)
      all.onTextAvailable(event, ProcessOutputTypes.STDERR)
      val empty = processListener {}
      empty.startNotified(event)
      empty.onTextAvailable(event, ProcessOutputTypes.STDOUT)
      empty.processWillTerminate(event, false)
      empty.processTerminated(event)
      empty.processNotStarted()
      assertEquals(listOf("start", "first", "destroyed true", "terminated", "not started"), calls)
      val disposed = processListener { onTextAvailable { _, _ -> calls += "disposed" } }
      handler.addProcessListener(disposed, parent)
      Disposer.dispose(parent)
      handler.notifyTextAvailable("third", ProcessOutputTypes.STDOUT)
      assertEquals(5, calls.size)
    } finally {
      Disposer.dispose(parent)
      handler.destroyProcess()
    }
  }

  @Test
  fun `document callbacks retain bulk events thread identity and explicit disposable lifetime`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val factory = EditorFactory.getInstance()
          val document = factory.createDocument("before")
          val parent = Disposer.newDisposable()
          val calls = mutableListOf<String>()
          val events = mutableListOf<DocumentEvent>()
          val receivers = mutableListOf<DocumentListener>()
          val thread = Thread.currentThread()
          try {
            val all = documentListener {
              beforeDocumentChange { if (it.document === document) calls += "before" }
              documentChanged {
                if (it.document === document) {
                  calls += "changed"
                  events += it
                }
              }
              bulkUpdateStarting { if (it === document) calls += "bulk start" }
              bulkUpdateFinished { if (it === document) calls += "bulk finish" }
            }
            factory.eventMulticaster.addDocumentListener(all, parent)
            val registered =
                factory.eventMulticaster.onDocumentChanged(parent) {
                  if (it.document === document) {
                    assertSame(thread, Thread.currentThread())
                    receivers += this
                  }
                }
            runWriteAction {
              DocumentUtil.executeInBulk(document, true) { document.setText("after") }
            }
            assertEquals(listOf("bulk start", "before", "changed", "bulk finish"), calls)
            assertSame(registered, receivers.single())
            val event = events.single()
            val empty = documentListener {}
            empty.beforeDocumentChange(event)
            empty.documentChanged(event)
            empty.bulkUpdateStarting(document)
            empty.bulkUpdateFinished(document)
            val failure = IllegalStateException("document listener failed")
            val throwing = documentListener { documentChanged { throw failure } }
            assertSame(
                failure,
                assertThrows<IllegalStateException> { throwing.documentChanged(event) },
            )
            Disposer.dispose(parent)
            runWriteAction { document.setText("disposed") }
            assertEquals(4, calls.size)
            assertEquals(1, receivers.size)
          } finally {
            Disposer.dispose(parent)
          }
        }
      }
}
