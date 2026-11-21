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
                val required = UIUtil.findComponentsOfType(root, JCheckBox::class.java).single()
                assertEquals(4, count.value)
                assertFalse(required.isSelected)
                assertFalse(page.isModified)
                count.value = 2
                required.isSelected = true
                assertTrue(page.isModified)
                page.reset()
                assertEquals(4, count.value)
                count.value = 1
                required.isSelected = true
                page.apply()
                assertEquals(1, policy.releasedHints)
                assertTrue(policy.requirePredictions)
                assertFalse(page.isModified)
            } finally { page.disposeUIResources() }
        }
    }
}
