@file:Suppress("UsePropertyAccessSyntax")

package edu.missouristate.mars.plugin.learning

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.cli.grading.ConsoleCheckCase
import edu.missouristate.mars.cli.grading.FunctionCallCheck
import edu.missouristate.mars.cli.grading.FunctionCheckCase
import edu.missouristate.mars.cli.grading.FunctionHelperCheck
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.learning.MarsDownloadedAssessmentCheckpoint.Feedback
import edu.missouristate.mars.plugin.run.MarsRunService
import edu.missouristate.mars.plugin.run.processListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsDownloadedAssessmentCheckpointTest {
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)
  private val identity = MarsLessonIdentity("tests", "offset-by-two", "0.4.0", "a".repeat(64))
  private val correct =
      $$"""
      .text
      .globl main
      main:
          li $v0, 5
          syscall
          addiu $t3, $v0, 2
          move $a0, $t3
          li $v0, 1
          syscall
          li $v0, 10
          syscall
      """
          .trimIndent() + "\n"
  private val step =
      MarsNativeAssessmentStep(
          "offset",
          "Write a generic console program.",
          "task.asm",
          listOf("cases.json"),
          listOf(0, 3, -3).map { ConsoleCheckCase("input $it", "$it\n", "${it + 2}", 20_000) },
          "Explain the calculation.",
          emptyList(),
      )
  private var fileNumber = 0

  private fun machine() = MarsSessionService.createMarsComponent(backstepLimit = 1).context()

  private data class Fixture(
      val checkpoint: MarsDownloadedAssessmentCheckpoint,
      val document: Document,
      val lifetime: Disposable,
  )

  private suspend fun exercise(
      text: String = correct,
      predictions: () -> Boolean = { true },
      create: () -> MarsContext = ::machine,
      selectedStep: MarsNativeAssessmentStep = step,
      body: suspend (Fixture) -> Unit,
  ) {
    val lifetime = Disposer.newDisposable()
    try {
      val state =
          withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("assessment-${fileNumber++}.asm", text).virtualFile
            fixture.configureFromExistingVirtualFile(file)
            Fixture(
                MarsDownloadedAssessmentCheckpoint(
                    projects.get(),
                    lifetime,
                    file.toNioPath(),
                    fixture.editor.document,
                    identity,
                    selectedStep,
                    predictions,
                    create,
                ),
                fixture.editor.document,
                lifetime,
            )
          }
      body(state)
    } finally {
      withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
    }
  }

  @Test
  fun `function profile batches retain original budgets and complete as one assessment`() =
      timeoutRunBlocking {
        val cases =
            (0..19).map { input ->
              FunctionCheckCase(
                  "value $input",
                  listOf(
                      FunctionCallCheck(
                          "identity",
                          "returned",
                          mapOf("a0" to input.toLong()),
                          mapOf("v0" to input.toLong()),
                      ),
                  ),
                  instructionLimit = 1_000_000,
              )
            }
        val selected =
            step.copy(
                cases = emptyList(),
                functionCases = cases,
                functionCaseBatches = cases.chunked(10),
            )
        val source =
            $$".text\n.globl main\nmain: li $v0,10\nsyscall\nreturned: nop\nidentity: move $v0,$a0\njrc $ra\n"
        val machines = mutableListOf<MarsContext>()
        exercise(source, create = { machine().also(machines::add) }, selectedStep = selected) { f ->
          val result = assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
          assertEquals(cases.map { it.name }, result.results.map { it.name })
          assertTrue(result.results.all { it.passed })
          assertTrue(f.checkpoint.passed())
          assertEquals(1, result.completed)
          assertEquals(20, machines.toSet().size)
          machines.forEach { assertThrows<IllegalStateException> { it.claimStoppedSession() } }
        }
      }

  @Test
  fun `console batches retain failures in later profiles and consume one attempt`() =
      timeoutRunBlocking {
        val cases =
            step.cases.mapIndexed { index, case ->
              if (index == 2) case.copy(expectedOutput = "incorrect") else case
            }
        val selected = step.copy(cases = cases, consoleCaseBatches = cases.chunked(2))
        exercise(selectedStep = selected) { f ->
          val result = assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
          assertEquals(listOf(true, true, false), result.results.map { it.passed })
          assertEquals(1, result.completed)
          assertFalse(f.checkpoint.passed())
        }
      }

  @Test
  fun `cancellation between profile batches consumes no attempt and closes earlier machines`() =
      timeoutRunBlocking {
        val selected = step.copy(consoleCaseBatches = step.cases.chunked(2))
        val machines = mutableListOf<MarsContext>()
        exercise(
            selectedStep = selected,
            create = {
              if (machines.size == 2) throw CancellationException("Cancel the next profile")
              machine().also(machines::add)
            },
        ) { f ->
          assertThrows<CancellationException> { f.checkpoint.check() }
          assertEquals(0, count())
          assertFalse(f.checkpoint.passed())
          assertEquals(2, machines.size)
          machines.forEach { assertThrows<IllegalStateException> { it.claimStoppedSession() } }
        }
      }

  @Test
  fun `current unsaved text accepts equivalent algorithms and closes independent case machines`() =
      timeoutRunBlocking {
        val machines = mutableListOf<MarsContext>()
        exercise(
            text =
                correct
                    .replace($$"addiu $t3, $v0, 2", $$"addiu $s2, $v0, 1\n    addiu $s2, $s2, 1")
                    .replace($$"move $a0, $t3", $$"move $a0, $s2"),
            create = { machine().also { machines += it } },
        ) { f ->
          assertTrue(f.checkpoint.accepts(f.document))
          assertFalse(f.checkpoint.accepts(null))
          assertFalse(f.checkpoint.accepts(EditorFactory.getInstance().createDocument("different")))
          val result = assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
          assertTrue(result.results.all { it.passed })
          assertEquals(1, result.completed)
          assertEquals(0, result.limit)
          assertTrue(f.checkpoint.passed())
          assertEquals(3, machines.size)
          assertEquals(3, machines.toSet().size)
          machines.forEach { assertThrows<IllegalStateException> { it.simulator() } }
          withContext(Dispatchers.EDT) { Disposer.dispose(f.lifetime) }
          assertFalse(f.checkpoint.accepts(f.document))
          assertTrue(f.checkpoint.passed())
          assertEquals(Feedback.STALE, f.checkpoint.check())
          val resumedLifetime = Disposer.newDisposable()
          try {
            val resumed =
                withContext(Dispatchers.EDT) {
                  val entry = FileDocumentManager.getInstance().getFile(f.document)!!.toNioPath()
                  MarsDownloadedAssessmentCheckpoint(
                      projects.get(),
                      resumedLifetime,
                      entry,
                      f.document,
                      identity,
                      step,
                      { true },
                  )
                }
            assertTrue(resumed.passed())
            assertEquals(1, count())
          } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(resumedLifetime) }
          }
        }
      }

  @Test
  fun `callable checks reject lost saved state and accept unsaved wrapper and tail implementations`() =
      timeoutRunBlocking {
        val header =
            $$"""
            .text
            .globl main
            main: li $v0,10
            syscall
            returned: bc main
            another_return: bc main
            invert:
            """
                .trimIndent() + "\n"
        val wrapper =
            $$"""
            daddiu $sp,$sp,-16
            sd $ra,8($sp)
            balc mask_byte
            xori $v0,$v0,255
            ld $ra,8($sp)
            daddiu $sp,$sp,16
            jrc $ra
            """
                .trimIndent() + "\n"
        val helper = $$"mask_byte: andi $v0,$a0,255\nhelper_return: jrc $ra\n"
        val cases =
            listOf(0, 1, 128).map { input ->
              FunctionCheckCase(
                  "byte $input",
                  listOf("returned", "another_return").map { continuation ->
                    FunctionCallCheck(
                        "invert",
                        continuation,
                        mapOf("a0" to (0x1234567800000000L or input.toLong())),
                        mapOf("v0" to (input xor 255).toLong()),
                    )
                  },
                  initialRegisters = mapOf("s0" to 0x123456789abcdef0L),
                  preservedRegisters = listOf("s0"),
                  helpers = listOf(FunctionHelperCheck("mask_byte", "helper_return")),
              )
            }
        val functionStep = step.copy(cases = emptyList(), functionCases = cases)
        val machines = mutableListOf<MarsContext>()
        exercise(
            header + "li $" + "s0,0\n" + wrapper + helper,
            create = { machine().also(machines::add) },
            selectedStep = functionStep,
        ) { f ->
          val failed = assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
          assertEquals(3, failed.results.size)
          assertTrue(failed.results.none { it.passed })
          assertTrue(failed.results.all { it.failure!!.contains("Expected s0") })
          assertEquals(1, failed.completed)
          assertFalse(f.checkpoint.passed())
          suspend fun edit(body: String) =
              withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(projects.get()) {
                  f.document.setText(header + body + helper)
                }
              }
          edit(wrapper)
          val passed = assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
          assertTrue(passed.results.all { it.passed })
          assertEquals(2, passed.completed)
          assertTrue(f.checkpoint.passed())
          edit($$"xori $a0,$a0,255\nbc mask_byte\n")
          assertFalse(f.checkpoint.passed())
          val equivalent = assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
          assertTrue(equivalent.results.all { it.passed })
          assertEquals(3, equivalent.completed)
          assertEquals(9, machines.toSet().size)
          machines.forEach { assertThrows<IllegalStateException> { it.claimStoppedSession() } }
        }
      }

  @Test
  fun `incorrect current programs consume one assessment and useful case failures do not require a source recipe`() =
      timeoutRunBlocking {
        exercise(correct.replace($$"addiu $t3, $v0, 2", $$"move $t3, $v0")) { f ->
          val result = assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
          assertTrue(result.results.none { it.passed })
          assertEquals(1, result.completed)
          assertFalse(f.checkpoint.passed())
        }
      }

  @Test
  fun `assembly failure unsupported source capture and cancellation use no attempt`() =
      timeoutRunBlocking {
        exercise("not_an_instruction\n") { f ->
          assertEquals(Feedback.ASSEMBLY, f.checkpoint.check())
          assertEquals(0, count())
        }
        withContext(Dispatchers.EDT) { fixture.addFileToProject("extra.inc", "# extra source\n") }
        exercise("$correct.include \"extra.inc\"\n") { f ->
          assertEquals(Feedback.STALE, f.checkpoint.check())
          assertEquals(0, count())
        }
        exercise(create = { throw CancellationException("Canceled test assessment") }) { f ->
          assertThrows<CancellationException> { f.checkpoint.check() }
          assertEquals(0, count())
          assertFalse(f.checkpoint.passed())
        }
      }

  @Test
  fun `matching console output cannot grant memory credit until the current image satisfies the byte contract`() =
      timeoutRunBlocking {
        val profile =
            """{"formatVersion":2,"cases":[{"name":"memory","input":"","stdout":"","instructionLimit":1000,"memory":[{"address":"0x0000000010010000","bytes":"9966aa00df9b5713"}]}]}"""
        val memoryStep = step.copy(cases = MarsConsoleCaseData.parse(profile.toByteArray()))
        val failed = $$".data\n.word 0, 0x13579bdf\n.text\nli $v0,10\nsyscall\n"
        val lifetime = Disposer.newDisposable()
        try {
          val checkpoint =
              withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("memory-check.asm", failed).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                MarsDownloadedAssessmentCheckpoint(
                    projects.get(),
                    lifetime,
                    file.toNioPath(),
                    fixture.editor.document,
                    identity,
                    memoryStep,
                    { true },
                )
              }
          val wrong = assertInstanceOf<Feedback.Checked>(checkpoint.check())
          assertFalse(wrong.results.single().passed)
          assertTrue(wrong.results.single().failure!!.contains("Expected byte"))
          assertFalse(checkpoint.passed())
          assertEquals(1, count())
          val correct =
              failed.replace(".text", $$".text\nli $t5,0xaa6699\nli $s1,0x10010000\nsw $t5,0($s1)")
          edit(fixture.editor.document, correct)
          assertTrue(
              assertInstanceOf<Feedback.Checked>(checkpoint.check()).results.single().passed,
          )
          assertTrue(checkpoint.passed())
          assertEquals(2, count())
        } finally {
          withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
      }

  @Test
  fun `matching final state cannot replace the required repeated device transactions`() =
      timeoutRunBlocking {
        val profile =
            """{"formatVersion":3,"cases":[{"name":"two writes","input":"","stdout":"","instructionLimit":1000,"initialMemory":[{"address":"0x0000000010010000","bytes":"07000000"}],"memory":[{"address":"0x0000000010010000","bytes":"07000000"}],"stores":[{"kind":"bytes","address":"0x00000000ffff0000","length":65536,"port":"0x00000000ffff0010","bytes":"0707"},{"kind":"none","address":"0x0000000010010000","length":4}],"reads":[{"address":"0x0000000010010000","length":4,"allowedAddress":"0x0000000010010000","allowedLength":1}]}]}"""
        val traceStep = step.copy(cases = MarsConsoleCaseData.parse(profile.toByteArray()))
        val failed =
            $$".data\n.word 0\n.text\nlbu $t0,0x10010000\ndli $t7,0xffff0010\nsb $t0,0($t7)\nli $v0,10\nsyscall\n"
        val lifetime = Disposer.newDisposable()
        try {
          val checkpoint =
              withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("trace-check.asm", failed).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                MarsDownloadedAssessmentCheckpoint(
                    projects.get(),
                    lifetime,
                    file.toNioPath(),
                    fixture.editor.document,
                    identity,
                    traceStep,
                    { true },
                )
              }
          val wrong = assertInstanceOf<Feedback.Checked>(checkpoint.check())
          assertFalse(wrong.results.single().passed)
          assertTrue(wrong.results.single().failure!!.contains("Expected 2 byte stores"))
          assertFalse(checkpoint.passed())
          assertEquals(1, count())
          edit(fixture.editor.document, failed.replace($$"li $v0,10", $$"sb $t0,0($t7)\nli $v0,10"))
          assertTrue(
              assertInstanceOf<Feedback.Checked>(checkpoint.check()).results.single().passed,
          )
          assertTrue(checkpoint.passed())
          assertEquals(2, count())
        } finally {
          withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
      }

  @Test
  fun `instructor gates and per-activity budgets are checked without erasing previously valid evidence`() =
      timeoutRunBlocking {
        val policy = projects.get().service<MarsLearningPolicy>()
        var predictions = false
        exercise(predictions = { predictions }) { f ->
          policy.update(allowFreeWriting = false)
          assertEquals(Feedback.POLICY, f.checkpoint.check())
          policy.update(allowFreeWriting = true, requirePredictions = true)
          assertEquals(Feedback.POLICY, f.checkpoint.check())
          assertFalse(f.checkpoint.passed())
          predictions = true
          policy.update(maxIndependentAssessments = 1)
          assertTrue(
              assertInstanceOf<Feedback.Checked>(f.checkpoint.check()).results.all {
                it.passed
              },
          )
          assertEquals(Feedback.LIMIT, f.checkpoint.check())
          assertTrue(f.checkpoint.passed())
          predictions = false
          assertFalse(f.checkpoint.passed())
          predictions = true
          assertTrue(f.checkpoint.passed())
          edit(f.document, "$correct# changed\n")
          assertFalse(f.checkpoint.passed())
          assertEquals(Feedback.LIMIT, f.checkpoint.check())
          assertEquals(1, count())
          policy.update(maxIndependentAssessments = 2)
          val second = assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
          assertEquals(2, second.completed)
          assertEquals(2, second.limit)
        }
      }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `edits including edits restored to original source cannot accept an in-flight result`(
      restore: Boolean,
  ) = timeoutRunBlocking {
    lateinit var document: Document
    var changed = false
    exercise(
        create = {
          if (!changed) {
            changed = true
            ApplicationManager.getApplication().invokeAndWait {
              WriteCommandAction.runWriteCommandAction(projects.get()) {
                document.setText("$correct# in flight\n")
                if (restore) document.setText(correct)
              }
            }
          }
          machine()
        },
    ) { f ->
      document = f.document
      assertEquals(Feedback.STALE, f.checkpoint.check())
      assertFalse(f.checkpoint.passed())
      assertEquals(0, count())
      assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
      assertTrue(f.checkpoint.passed())
    }
  }

  @Test
  fun `reset disposal and policy changes during execution discard results without consuming attempts`() =
      timeoutRunBlocking {
        val project = projects.get()
        val policy = project.service<MarsLearningPolicy>()
        for (kind in listOf("reset", "dispose", "policy")) {
          project.service<MarsLearningProgress>().reset(identity)
          policy.update(allowFreeWriting = true)
          lateinit var lifetime: Disposable
          exercise(
              create = {
                when (kind) {
                  "reset" -> project.service<MarsLearningProgress>().reset(identity)
                  "dispose" -> Disposer.dispose(lifetime)
                  else -> policy.update(allowFreeWriting = false)
                }
                machine()
              },
          ) { f ->
            lifetime = f.lifetime
            assertEquals(Feedback.STALE, f.checkpoint.check())
            assertFalse(f.checkpoint.passed())
            assertEquals(0, count())
          }
        }
      }

  @Test
  fun `an assessment limit reduced while checking prevents acceptance`() = timeoutRunBlocking {
    val progress = projects.get().service<MarsLearningProgress>()
    val policy = projects.get().service<MarsLearningPolicy>()
    exercise(
        create = {
          policy.update(maxIndependentAssessments = 1)
          machine()
        },
    ) { f ->
      progress.finishAssessment(
          progress.beginCheck(identity, "previous", MarsLearningSource.revision(correct)),
          false,
          0,
      )
      assertEquals(1, count())
      assertEquals(Feedback.STALE, f.checkpoint.check())
      assertEquals(1, count())
      assertFalse(f.checkpoint.passed())
    }
  }

  @Test
  fun `reset records restart at zero and deleted source files cannot be selected for assessment`() =
      timeoutRunBlocking {
        val project = projects.get()
        exercise { f ->
          project.service<MarsLearningPolicy>().update(maxIndependentAssessments = 1)
          project.service<MarsLearningProgress>().reset(identity)
          assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
          assertEquals(1, count())
          withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
              FileDocumentManager.getInstance().getFile(f.document)!!.delete(this)
            }
          }
          assertFalse(f.checkpoint.accepts(f.document))
        }
      }

  @Test
  fun `a disposed assessment suppresses feedback even when its progress owner remains active`() =
      timeoutRunBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        exercise(
            create = {
              entered.complete(Unit)
              check(release.await(15, TimeUnit.SECONDS))
              machine()
            },
        ) { f ->
          val owner = Disposer.newDisposable()
          var feedback = 0
          try {
            val job =
                projects.get().service<MarsLearningExecution>().checkAssessment(
                    owner,
                    f.checkpoint,
                ) {
                  feedback++
                }
            entered.await()
            withContext(Dispatchers.EDT) { Disposer.dispose(f.lifetime) }
            release.countDown()
            job.join()
            assertEquals(0, feedback)
            assertEquals(0, count())
          } finally {
            release.countDown()
            Disposer.dispose(owner)
          }
        }
      }

  @Test
  fun `only the latest overlapping check can consume an attempt and grant evidence`() =
      timeoutRunBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val starts = AtomicInteger()
        exercise(
            create = {
              if (starts.incrementAndGet() == 1) {
                entered.complete(Unit)
                check(release.await(15, TimeUnit.SECONDS))
              }
              machine()
            },
        ) { f ->
          coroutineScope {
            val old = async(Dispatchers.Default) { f.checkpoint.check() }
            try {
              entered.await()
              assertInstanceOf<Feedback.Checked>(f.checkpoint.check())
              release.countDown()
              assertEquals(Feedback.STALE, old.await())
              assertEquals(1, count())
              assertTrue(f.checkpoint.passed())
            } finally {
              release.countDown()
            }
          }
        }
      }

  @Test
  fun `a running student program keeps its own console input output and registers while grading`() =
      timeoutRunBlocking {
        val project = projects.get()
        val waiting = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Int>()
        val stdout = StringBuffer()
        val live =
            withContext(Dispatchers.EDT) {
              val source =
                  correct
                      .replace(".text", ".data\nprompt: .asciiz \"Waiting: \"\n.text")
                      .replace(
                          $$"    li $v0, 5",
                          $$"    la $a0, prompt\n    li $v0, 4\n    syscall\n    li $v0, 5",
                      )
              val file = fixture.addFileToProject("live.asm", source).virtualFile
              project
                  .service<MarsRunService>()
                  .createProcess(file.toNioPath(), memoryDumps = emptyList())
            }
        live.addProcessListener(
            processListener {
              onTextAvailable { event, outputType ->
                if (ProcessOutputType.isStdout(outputType)) {
                  stdout.append(event.text)
                  if (stdout.toString().contains("Waiting: ")) waiting.complete(Unit)
                }
              }
              processTerminated { event ->
                finished.complete(event.exitCode)
              }
            },
        )
        try {
          live.startNotify()
          waiting.await()
          exercise { f ->
            assertTrue(
                assertInstanceOf<Feedback.Checked>(f.checkpoint.check()).results.all {
                  it.passed
                },
            )
          }
          assertFalse(finished.isCompleted)
          assertEquals("Waiting: ", stdout.toString())
          withContext(Dispatchers.IO) {
            live.processInput.apply {
              write("10\n".toByteArray())
              flush()
            }
          }
          assertEquals(0, finished.await())
          assertEquals("Waiting: 12", stdout.toString())
        } finally {
          live.destroyProcess()
          finished.await()
        }
      }

  private fun count() =
      projects.get().service<MarsLearningProgress>().snapshot(identity)?.completedAssessments ?: 0

  private suspend fun edit(document: Document, text: String) =
      withContext(Dispatchers.EDT) {
        WriteCommandAction.runWriteCommandAction(projects.get()) { document.setText(text) }
      }
}
