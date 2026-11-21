package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.xmlb.XmlSerializer
import edu.missouristate.mars.plugin.classicMipsProjectFixture
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsLearningProgressTest {
    private val project = classicMipsProjectFixture()
    private val lesson = MarsLessonIdentity("mars-book", "console", "1.0.0", "a".repeat(64))
    private val other = lesson.copy(lesson = "pixel")

    @Test
    fun `the project owns its learning progress and unrelated services are independent`() {
        val service = project.get().service<MarsLearningProgress>()
        val isolated = MarsLearningProgress()
        service.open(lesson, "source-a")
        service.recordChoice(lesson, "predict", "forty")
        assertNull(isolated.snapshot(lesson))
        assertEquals("forty", service.snapshot(lesson)!!.quizAttempts.single().choice)
        service.reset(lesson)
    }

    @Test
    fun `a source edit invalidates checks while retaining predictions and hints`() {
        val progress = MarsLearningProgress()
        assertNull(progress.snapshot(lesson))
        progress.open(lesson, "source-a")
        progress.recordChoice(lesson, "prediction", "wrong")
        progress.recordChoice(lesson, "prediction", "revised")
        progress.revealHint(lesson, "hint-one")
        progress.revealHint(lesson, "hint-one")
        assertTrue(progress.finishCheck(progress.beginCheck(lesson, "arithmetic", "source-a"), true))
        val checked = progress.snapshot(lesson)!!
        assertEquals(setOf("arithmetic"), checked.successfulChecks)
        progress.open(lesson, "source-a")
        assertEquals(checked, progress.snapshot(lesson))
        progress.open(lesson, "source-b")
        val edited = progress.snapshot(lesson)!!
        assertTrue(edited.successfulChecks.isEmpty())
        assertEquals(listOf("wrong", "revised"), edited.quizAttempts.map { it.choice })
        assertEquals(setOf("hint-one"), edited.revealedHints)
        assertEquals("source-b", edited.sourceRevision)
        // Returning to old source must not resurrect its previous successful result.
        progress.open(lesson, "source-a")
        assertTrue(progress.snapshot(lesson)!!.successfulChecks.isEmpty())
        assertEquals(setOf("arithmetic"), checked.successfulChecks)
    }

    @Test
    fun `late results cannot pass edited work including editing back to identical source`() {
        val progress = MarsLearningProgress()
        val old = progress.beginCheck(lesson, "output", "a")
        progress.open(lesson, "b")
        progress.open(lesson, "a")
        assertFalse(progress.finishCheck(old, true))
        assertTrue(progress.snapshot(lesson)!!.successfulChecks.isEmpty())
    }

    @Test
    fun `only the latest retry may finish and failed retries remove an earlier pass`() {
        val progress = MarsLearningProgress()
        val first = progress.beginCheck(lesson, "output", "a")
        val retry = progress.beginCheck(lesson, "output", "a")
        assertFalse(progress.finishCheck(first, true))
        assertTrue(progress.finishCheck(retry, true))
        assertFalse(progress.finishCheck(retry, false))
        assertEquals(setOf("output"), progress.snapshot(lesson)!!.successfulChecks)
        val failed = progress.beginCheck(lesson, "output", "a")
        assertTrue(progress.snapshot(lesson)!!.successfulChecks.isEmpty())
        assertTrue(progress.finishCheck(failed, false))
        assertTrue(progress.snapshot(lesson)!!.successfulChecks.isEmpty())
    }

    @Test
    fun `different steps and lessons have independent requests and resets`() {
        val progress = MarsLearningProgress()
        val a = progress.beginCheck(lesson, "a", "source-a")
        val b = progress.beginCheck(lesson, "b", "source-a")
        val c = progress.beginCheck(other, "a", "source-other")
        assertTrue(progress.finishCheck(a, true))
        assertTrue(progress.finishCheck(b, true))
        assertEquals(setOf("a", "b"), progress.snapshot(lesson)!!.successfulChecks)
        val abandoned = progress.beginCheck(lesson, "a", "source-a")
        progress.reset(lesson)
        assertNull(progress.snapshot(lesson))
        assertFalse(progress.finishCheck(abandoned, true))
        assertTrue(progress.finishCheck(c, true))
        assertEquals(setOf("a"), progress.snapshot(other)!!.successfulChecks)
        // An edit to one lesson also leaves another lesson's pending checks alone.
        val otherPending = progress.beginCheck(other, "b", "source-other")
        progress.open(lesson, "source-a")
        progress.open(lesson, "source-b")
        assertTrue(progress.finishCheck(otherPending, true))
    }

    @Test
    fun `content editions and course identifiers keep separate histories`() {
        val progress = MarsLearningProgress()
        val identities = listOf(lesson, lesson.copy(course = "course-two"),
            lesson.copy(version = "1.1.0"), lesson.copy(contentSha256 = "b".repeat(64)))
        identities.forEachIndexed { n, identity ->
            progress.open(identity, "same-source")
            progress.recordChoice(identity, "predict", "choice-$n")
        }
        assertEquals(4, progress.getState().lessons.size)
        identities.forEachIndexed { n, identity ->
            assertEquals("choice-$n", progress.snapshot(identity)!!.quizAttempts.single().choice)
        }
    }

    @Test
    fun `real platform serialization restores evidence but not in-flight requests`() {
        val progress = MarsLearningProgress()
        progress.open(lesson, "source-a")
        progress.recordChoice(lesson, "q", "a")
        progress.revealHint(lesson, "hint")
        assertTrue(progress.finishCheck(progress.beginCheck(lesson, "done", "source-a"), true))
        val pending = progress.beginCheck(lesson, "pending", "source-a")
        val xml = XmlSerializer.serialize(progress.getState())
        val restored = MarsLearningProgress()
        restored.loadState(XmlSerializer.deserialize(xml, MarsLearningProgress.Progress::class.java))
        assertEquals(progress.snapshot(lesson), restored.snapshot(lesson))
        assertFalse(restored.finishCheck(pending, true))
        progress.loadState(XmlSerializer.deserialize(xml, MarsLearningProgress.Progress::class.java))
        assertFalse(progress.finishCheck(pending, true))
        assertEquals(setOf("done"), restored.snapshot(lesson)!!.successfulChecks)
        val snapshot = restored.snapshot(lesson)!!
        snapshot.quizAttempts.single().choice = "changed externally"
        snapshot.sourceRevision = "changed externally"
        assertEquals("a", restored.snapshot(lesson)!!.quizAttempts.single().choice)
        assertEquals("source-a", restored.snapshot(lesson)!!.sourceRevision)
        assertNotSame(snapshot, restored.snapshot(lesson))
    }

    @Test
    fun `different service instances cannot complete each others requests`() {
        val first = MarsLearningProgress()
        val second = MarsLearningProgress()
        val a = first.beginCheck(lesson, "output", "source")
        val b = second.beginCheck(lesson, "output", "source")
        assertFalse(first.finishCheck(b, true))
        assertFalse(second.finishCheck(a, true))
        assertTrue(first.finishCheck(a, true))
        assertTrue(second.finishCheck(b, true))
    }

    @Test
    fun `concurrent observations are retained without losing attempts`() {
        val progress = MarsLearningProgress()
        progress.open(lesson, "source")
        Executors.newFixedThreadPool(4).use { pool ->
            (0 until 40).map { n -> pool.submit { progress.recordChoice(lesson, "q", "choice-$n") } }
                .forEach { it.get() }
        }
        assertEquals((0 until 40).map { "choice-$it" }.toSet(),
            progress.snapshot(lesson)!!.quizAttempts.map { it.choice }.toSet())
        assertTrue(progress.stateModificationCount >= 41)
    }

    @Test
    fun `recording an answer or hint requires an opened lesson`() {
        val progress = MarsLearningProgress()
        assertThrows<IllegalStateException> { progress.recordChoice(lesson, "q", "a") }
        assertThrows<IllegalStateException> { progress.revealHint(lesson, "h") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\n"])
    fun `blank event identifiers cannot create progress`(blank: String) {
        val progress = MarsLearningProgress()
        assertThrows<IllegalArgumentException> { progress.open(lesson, blank) }
        assertThrows<IllegalArgumentException> { progress.beginCheck(lesson, blank, "source") }
        assertThrows<IllegalArgumentException> { progress.recordChoice(lesson, blank, "a") }
        assertThrows<IllegalArgumentException> { progress.recordChoice(lesson, "q", blank) }
        assertThrows<IllegalArgumentException> { progress.revealHint(lesson, blank) }
        assertTrue(progress.getState().lessons.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "../lesson", "a/b", "a b", "a\nb"])
    fun `identity components cannot collide with another lesson path`(invalid: String) {
        assertThrows<IllegalArgumentException> { lesson.copy(course = invalid) }
        assertThrows<IllegalArgumentException> { lesson.copy(lesson = invalid) }
        assertThrows<IllegalArgumentException> { lesson.copy(version = invalid) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"])
    fun `content identity requires a canonical SHA-256`(invalid: String) {
        assertThrows<IllegalArgumentException> { lesson.copy(contentSha256 = invalid) }
    }
}
