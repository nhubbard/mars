package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import javax.swing.JCheckBox
import javax.swing.JSpinner
import javax.swing.JTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MarsLearningPolicyTest {
    private val fixture = codeInsightProjectFixture()

    @Test
    fun `project policy is independent of personal progress and normalizes persisted hint bounds`() {
        val project = fixture.get()
        val policy = project.service<MarsLearningPolicy>()
        assertEquals(4, policy.releasedHints)
        assertFalse(policy.requirePredictions)
        assertTrue(policy.canStartIndependent())
        policy.update()
        policy.update(2, true)
        val xml = XmlSerializer.serialize(policy.getState())
        val restored = MarsLearningPolicy(project)
        restored.loadState(XmlSerializer.deserialize(xml, MarsLearningPolicy.Policy::class.java))
        assertEquals(2, restored.releasedHints)
        assertTrue(restored.requirePredictions)
        assertFalse(restored.canStartIndependent())
        policy.update(releasedHints = 0)
        assertTrue(policy.requirePredictions)
        policy.update(requirePredictions = false)
        assertEquals(0, policy.releasedHints)
        assertTrue(policy.canStartIndependent())
        assertEquals(2, restored.releasedHints)
        for ((stored, expected) in listOf(-1 to 0, 0 to 0, 4 to 4, 5 to 4)) {
            restored.loadState(MarsLearningPolicy.Policy(stored))
            assertEquals(expected, restored.releasedHints)
        }
        for (invalid in listOf(-1, 5)) assertThrows<IllegalArgumentException> { policy.update(releasedHints = invalid) }
        assertEquals(0, policy.releasedHints)
    }

    @Test
    fun `required predictions use current content and latest answers without granting source checks`() {
        val project = fixture.get()
        val policy = project.service<MarsLearningPolicy>()
        val progress = project.service<MarsLearningProgress>()
        policy.update(requirePredictions = true)
        assertFalse(policy.canStartIndependent())
        val identity = MarsPredictions.identity
        progress.open(identity, identity.contentSha256)
        progress.recordChoice(identity, "doubling", "b")
        assertFalse(policy.canStartIndependent())
        progress.recordChoice(identity, "doubling", MarsPredictions.doubling.correct)
        assertFalse(policy.canStartIndependent())
        progress.recordChoice(identity, "negative", MarsPredictions.negative.correct)
        assertTrue(policy.canStartIndependent())
        progress.recordChoice(identity, "negative", "a")
        assertFalse(policy.canStartIndependent())
        progress.recordChoice(identity, "negative", MarsPredictions.negative.correct)
        assertTrue(policy.canStartIndependent())
        progress.reset(identity)
        assertFalse(policy.canStartIndependent())
        assertTrue(progress.getState().lessons.isEmpty())
    }

    @Test
    fun `native project settings reset and apply release and prerequisite controls`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val policy = fixture.get().service<MarsLearningPolicy>()
            val page = MarsLearningConfigurable(fixture.get())
            try {
                assertEquals("MARS Learning", page.displayName)
                val root = page.createComponent()
                page.reset()
                val count = UIUtil.findComponentsOfType(root, JSpinner::class.java).single()
                val boxes = UIUtil.findComponentsOfType(root, JCheckBox::class.java)
                val required = boxes.single { it.text == learningMessages.getMessage("policy.predictions") }
                val free = boxes.single { it.text == learningMessages.getMessage("policy.free") }
                assertEquals(4, count.value)
                assertFalse(required.isSelected)
                assertTrue(free.isSelected)
                assertFalse(page.isModified)
                count.value = 2
                required.isSelected = true
                assertTrue(page.isModified)
                page.reset()
                assertEquals(4, count.value)
                count.value = 1
                required.isSelected = true
                free.isSelected = false
                boxes.single { it.text == MarsGuidedRequirement.RUN.title }.isSelected = true
                UIUtil.findComponentsOfType(root, JTextField::class.java).single { it.accessibleContext.accessibleName == learningMessages.getMessage("policy.assessments") }.text = "3"
                page.apply()
                assertEquals(3, policy.maxIndependentAssessments)
                assertEquals(1, policy.releasedHints)
                assertTrue(policy.requirePredictions)
                assertFalse(policy.allowFreeWriting)
                assertEquals(setOf("run"), policy.requiredGuidedChecks)
                assertFalse(page.isModified)
            } finally { page.disposeUIResources() }
        }
    }
    @Test
    fun `assessment policy defaults unlimited and preserves bounded persisted instructor settings`() {
        val project = fixture.get()
        val policy = project.service<MarsLearningPolicy>()
        assertEquals(0, policy.maxIndependentAssessments)
        assertEquals(0, policy.completedIndependentAssessments())
        assertTrue(policy.canAssessIndependent())
        policy.update(maxIndependentAssessments = 1)
        val progress = project.service<MarsLearningProgress>()
        val identity = MarsIndependentCheckpoint.identity
        assertTrue(progress.finishAssessment(progress.beginCheck(identity, MarsIndependentCheckpoint.STEP, "source"), false, 1))
        assertFalse(policy.canAssessIndependent())
        assertTrue(policy.canStartIndependent()) // Exhaustion must not revoke an earned pass or hide optional hints.
        policy.update(releasedHints = 2)
        assertEquals(1, policy.maxIndependentAssessments)
        policy.update(maxIndependentAssessments = 2)
        assertTrue(policy.canAssessIndependent())
        val restored = MarsLearningPolicy(project)
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(policy.getState()), MarsLearningPolicy.Policy::class.java))
        assertEquals(2, restored.maxIndependentAssessments)
        for ((stored, expected) in listOf(-1 to 0, 1001 to 1000)) {
            restored.loadState(MarsLearningPolicy.Policy(maxIndependentAssessments = stored))
            assertEquals(expected, restored.maxIndependentAssessments)
        }
        for (invalid in listOf(-1, 1001)) assertThrows<IllegalArgumentException> { policy.update(maxIndependentAssessments = invalid) }
        policy.update(maxIndependentAssessments = 0)
        assertTrue(policy.canAssessIndependent())
    }

}
