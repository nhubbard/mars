@file:Suppress("JetBrainsInternalApiUsage")

package edu.missouristate.mars.plugin.learning

import com.intellij.configurationStore.deserialize
import com.intellij.mock.MockProject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsGuidedRequirementTest {
  private val fixture = codeInsightProjectFixture()

  private suspend fun document(check: MarsGuidedRequirement): Document {
    val file =
        withContext(Dispatchers.IO) {
          val path = MarsLearningWorkspace.ensureExample(fixture.get(), check.exercise)
          checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path))
        }
    return readAction { checkNotNull(FileDocumentManager.getInstance().getDocument(file)) }
  }

  private fun pass(check: MarsGuidedRequirement, document: Document) {
    val project = fixture.get()
    val source = MarsLearningSource(project, check.source(project)!!, document, check.identity)
    assertTrue(source.finish(source.begin(check.step), true))
  }

  @Test
  fun `each selected observation requires current source and round trips with the course policy`() =
      timeoutRunBlocking {
        val project = fixture.get()
        val policy = project.service<MarsLearningPolicy>()
        assertTrue(policy.requiredGuidedChecks.isEmpty())
        assertTrue(policy.independentRestriction().contains("requirements are satisfied"))
        val beforeRequirements = policy.refreshGuidedSources()
        policy.setGuidedRequirement(MarsGuidedRequirement.RUN, true)
        assertFalse(policy.canStartIndependent(beforeRequirements))
        policy.setGuidedRequirement(MarsGuidedRequirement.RUN, false)
        assertTrue(policy.verifyIndependentPrerequisites())
        for (check in MarsGuidedRequirement.entries) {
          policy.setGuidedRequirement(check, true)
          assertFalse(policy.canStartIndependent())
          assertTrue(policy.independentRestriction().contains(check.title))
          val document = document(check)
          assertFalse(check.passed(project))
          val progress = project.service<MarsLearningProgress>()
          progress.open(check.identity, MarsLearningSource.revision(document.text))
          assertFalse(check.passed(project))
          withContext(Dispatchers.EDT) { pass(check, document) }
          for ((id, _, _, correct) in check.predictions) {
            assertFalse(check.passed(project))
            progress.open(MarsPredictions.identity, MarsPredictions.identity.contentSha256)
            progress.recordChoice(MarsPredictions.identity, id, correct)
          }
          assertTrue(check.passed(project))
          assertTrue(policy.canStartIndependent())
          assertTrue(policy.verifyIndependentPrerequisites())
          for ((id, _, choices, correct) in check.predictions) {
            progress.recordChoice(
                MarsPredictions.identity,
                id,
                choices.first { it.id != correct }.id,
            )
            assertFalse(policy.canStartIndependent())
            progress.recordChoice(MarsPredictions.identity, id, correct)
            assertTrue(policy.canStartIndependent())
          }
        }
        val restored = MarsLearningPolicy(project)
        restored.loadState(
            deserialize<MarsLearningPolicy.Policy>(XmlSerializer.serialize(policy.getState())),
        )
        assertEquals(
            MarsGuidedRequirement.entries.map { it.id }.toSet(),
            restored.requiredGuidedChecks,
        )
        policy.update(releasedHints = 1, requirePredictions = true)
        assertEquals(restored.requiredGuidedChecks, policy.requiredGuidedChecks)
        for (check in MarsGuidedRequirement.entries) policy.setGuidedRequirement(check, false)
        assertTrue(policy.requiredGuidedChecks.isEmpty())
        assertFalse(policy.canStartIndependent())
      }

  @Suppress("UsePropertyAccessSyntax")
  @Test
  fun `source edits after leaving a lesson cannot resurrect evidence by editing back`() =
      timeoutRunBlocking {
        val project = fixture.get()
        val check = MarsGuidedRequirement.RUN
        val document = document(check)
        val original = document.text
        val progress = project.service<MarsLearningProgress>()
        val policy = project.service<MarsLearningPolicy>()
        policy.setGuidedRequirement(check, true)
        withContext(Dispatchers.EDT) { pass(check, document) }
        assertTrue(policy.canStartIndependent())
        withContext(Dispatchers.EDT) {
          WriteCommandAction.runWriteCommandAction(project) {
            document.setText("$original\n# changed after leaving the lesson\n")
          }
          WriteCommandAction.runWriteCommandAction(project) { document.setText(original) }
        }
        assertFalse(policy.canStartIndependent())
        assertTrue(progress.snapshot(check.identity)!!.successfulChecks.isEmpty())
        withContext(Dispatchers.EDT) { pass(check, document) }
        assertTrue(policy.canStartIndependent())
        progress.reset(check.identity)
        withContext(Dispatchers.EDT) {
          WriteCommandAction.runWriteCommandAction(project) {
            document.setText("$original\n# reset work stays uncredited\n")
          }
          val unbound = EditorFactory.getInstance().createDocument("unbound")
          WriteCommandAction.runWriteCommandAction(project) { unbound.setText("changed") }
        }
        assertNull(progress.snapshot(check.identity))
      }

  @Test
  fun `saved mismatched source missing files and directories cannot satisfy a required observation`() =
      timeoutRunBlocking {
        val project = fixture.get()
        val check = MarsGuidedRequirement.RUN
        val progress = project.service<MarsLearningProgress>()
        val policy = project.service<MarsLearningPolicy>()
        policy.setGuidedRequirement(check, true)
        assertFalse(check.passed(project))
        val document = document(check)
        assertTrue(
            progress.finishCheck(
                progress.beginCheck(check.identity, check.step, "old-source"),
                true,
            ),
        )
        assertFalse(check.passed(project))
        assertTrue(progress.snapshot(check.identity)!!.successfulChecks.isEmpty())
        withContext(Dispatchers.EDT) { pass(check, document) }
        val file = FileDocumentManager.getInstance().getFile(document)!!
        withContext(Dispatchers.EDT) {
          WriteCommandAction.runWriteCommandAction(project) { file.delete(this) }
        }
        assertFalse(policy.canStartIndependent())
        withContext(Dispatchers.IO) {
          Files.createDirectory(check.source(project)!!)
          VirtualFileManager.getInstance().refreshAndFindFileByNioPath(check.source(project)!!)
        }
        assertTrue(
            progress.finishCheck(
                progress.beginCheck(check.identity, check.step, "directory"),
                true,
            ),
        )
        assertFalse(check.passed(project))
      }

  @Test
  fun `fresh prerequisite validation sees an external edit without relying on a prior editor refresh`() =
      timeoutRunBlocking {
        val project = fixture.get()
        val check = MarsGuidedRequirement.RUN
        val policy = project.service<MarsLearningPolicy>()
        val document = document(check)
        policy.setGuidedRequirement(check, true)
        withContext(Dispatchers.EDT) { pass(check, document) }
        assertTrue(policy.canStartIndependent())
        val changed = document.text + "\n# Changed outside the editor\n"
        withContext(Dispatchers.IO) { Files.writeString(check.source(project)!!, changed) }
        assertFalse(policy.verifyIndependentPrerequisites())
        assertTrue(
            project
                .service<MarsLearningProgress>()
                .snapshot(check.identity)!!
                .successfulChecks
                .isEmpty(),
        )
        assertEquals(changed, document.text)
      }

  @Test
  fun `unknown persisted requirements fail closed and survive edits to other settings`() =
      timeoutRunBlocking {
        val policy = fixture.get().service<MarsLearningPolicy>()
        policy.loadState(
            MarsLearningPolicy.Policy(
                requiredGuidedChecks = setOf("future-activity", "<untrusted>"),
            ),
        )
        assertFalse(policy.canStartIndependent())
        assertTrue(policy.independentRestriction().contains("does not support"))
        assertFalse(policy.independentRestriction().contains("<untrusted>"))
        policy.update(releasedHints = 0)
        policy.setGuidedRequirement(MarsGuidedRequirement.RUN, true)
        assertEquals(setOf("future-activity", "<untrusted>", "run"), policy.requiredGuidedChecks)
        assertFalse(policy.verifyIndependentPrerequisites())
        val lifetime = Disposer.newDisposable()
        try {
          val noRoot = MockProject(null, lifetime)
          assertNull(MarsGuidedRequirement.RUN.source(noRoot))
          assertFalse(MarsGuidedRequirement.RUN.passed(noRoot))
          val noRootPolicy = MarsLearningPolicy(noRoot)
          noRootPolicy.setGuidedRequirement(MarsGuidedRequirement.RUN, true)
          assertFalse(noRootPolicy.verifyIndependentPrerequisites())
        } finally {
          Disposer.dispose(lifetime)
        }
      }
}
