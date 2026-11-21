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
    fun `recoverable reset persists a scoped copy and recovery keeps newer formative work without execution credit`() {
        val progress = MarsLearningProgress()
        val scope = setOf(lesson, other)
        val unrelated = lesson.copy(course = "another-course")
        assertFalse(progress.canReset(scope))
        assertFalse(progress.canRecover(scope))
        assertFalse(progress.resetRecoverably(scope))
        assertFalse(progress.recover(scope))
        progress.open(lesson, "source-a")
        progress.recordChoice(lesson, "q", "old")
        progress.revealHint(lesson, "first")
        assertTrue(progress.finishCheck(progress.beginCheck(lesson, "done", "source-a"), true))
        val stale = progress.beginCheck(lesson, "pending", "source-a")
        progress.open(other, "other-source")
        progress.recordChoice(other, "other-q", "choice")
        val outside = progress.beginCheck(unrelated, "outside", "outside-source")
        assertTrue(progress.canReset(scope))
        assertTrue(progress.resetRecoverably(scope))
        assertFalse(progress.isCurrent(stale))
        assertFalse(progress.finishCheck(stale, true))
        assertNull(progress.snapshot(lesson))
        assertNull(progress.snapshot(other))
        assertTrue(progress.canRecover(scope))
        assertFalse(progress.resetRecoverably(scope)) // Must not replace the saved copy with empty history.
        assertTrue(progress.finishCheck(outside, true))
        val restored = MarsLearningProgress()
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(progress.getState()), MarsLearningProgress.Progress::class.java))
        assertTrue(restored.canRecover(scope))
        restored.open(lesson, "edited-source")
        restored.recordChoice(lesson, "q", "new")
        restored.revealHint(lesson, "second")
        assertTrue(restored.finishCheck(restored.beginCheck(lesson, "new-pass", "edited-source"), true))
        val interrupted = restored.beginCheck(lesson, "pending", "edited-source")
        assertTrue(restored.recover(scope))
        assertFalse(restored.finishCheck(interrupted, true))
        assertEquals("edited-source", restored.snapshot(lesson)!!.sourceRevision)
        assertEquals(listOf("old", "new"), restored.snapshot(lesson)!!.quizAttempts.map { it.choice })
        assertEquals(setOf("first", "second"), restored.snapshot(lesson)!!.revealedHints)
        assertTrue(restored.snapshot(lesson)!!.successfulChecks.isEmpty())
        assertEquals("other-source", restored.snapshot(other)!!.sourceRevision)
        assertEquals("choice", restored.snapshot(other)!!.quizAttempts.single().choice)
        assertEquals(setOf("outside"), restored.snapshot(unrelated)!!.successfulChecks)
        assertFalse(restored.canRecover(scope))
        assertFalse(restored.recover(scope))
        // The original instance still owns its separate recovery copy.
        assertTrue(progress.canRecover(scope))
    }

    @Test
    fun `an opened empty lesson cannot replace recovery and each kind of real history permits reset`() {
        val scope = setOf(lesson)
        for (kind in listOf("quiz", "hint", "check")) {
            val progress = MarsLearningProgress()
            progress.open(lesson, "source")
            assertFalse(progress.canReset(scope))
            assertFalse(progress.resetRecoverably(scope))
            when (kind) {
                "quiz" -> progress.recordChoice(lesson, "q", "a")
                "hint" -> progress.revealHint(lesson, "hint")
                else -> assertTrue(progress.finishCheck(progress.beginCheck(lesson, "done", "source"), true))
            }
            assertTrue(progress.canReset(scope))
            assertTrue(progress.resetRecoverably(scope))
            val backup = progress.getState().recovery
            progress.open(lesson, "reopened-source")
            assertFalse(progress.canReset(scope))
            assertFalse(progress.resetRecoverably(scope))
            assertEquals(backup, progress.getState().recovery)
            assertTrue(progress.recover(scope))
        }
    }

    @Test
    fun `recovery also requires rechecking a segment first attempted after the reset`() {
        val progress = MarsLearningProgress()
        val scope = setOf(lesson, other)
        val unrelated = lesson.copy(course = "unrelated")
        progress.open(lesson, "source")
        progress.recordChoice(lesson, "q", "a")
        assertTrue(progress.resetRecoverably(scope))
        progress.open(other, "new-segment-source")
        progress.recordChoice(other, "new-q", "new-choice")
        progress.revealHint(other, "new-hint")
        assertTrue(progress.finishCheck(progress.beginCheck(other, "done", "new-segment-source"), true))
        val pending = progress.beginCheck(other, "pending", "new-segment-source")
        val outside = progress.beginCheck(unrelated, "outside", "outside-source")
        assertTrue(progress.recover(scope))
        assertTrue(progress.snapshot(other)!!.successfulChecks.isEmpty())
        assertEquals("new-choice", progress.snapshot(other)!!.quizAttempts.single().choice)
        assertEquals(setOf("new-hint"), progress.snapshot(other)!!.revealedHints)
        assertFalse(progress.finishCheck(pending, true))
        assertTrue(progress.finishCheck(outside, true))
    }

    @Test
    fun `a later nonempty reset replaces that scope only and editions cannot recover each others histories`() {
        val progress = MarsLearningProgress()
        val newer = lesson.copy(contentSha256 = "b".repeat(64))
        for (identity in listOf(lesson, other, newer)) {
            progress.open(identity, "source")
            progress.recordChoice(identity, "q", identity.lesson)
        }
        assertTrue(progress.resetRecoverably(setOf(lesson, other)))
        assertTrue(progress.resetRecoverably(setOf(newer)))
        progress.open(lesson, "new-source")
        progress.recordChoice(lesson, "q", "replacement")
        assertTrue(progress.resetRecoverably(setOf(lesson)))
        assertTrue(progress.recover(setOf(lesson)))
        assertEquals(listOf("replacement"), progress.snapshot(lesson)!!.quizAttempts.map { it.choice })
        assertTrue(progress.canRecover(setOf(other, newer)))
        assertFalse(progress.canRecover(emptySet()))
        assertFalse(progress.canReset(emptySet()))
        assertFalse(progress.resetRecoverably(emptySet()))
        assertFalse(progress.recover(emptySet()))
        assertTrue(progress.recover(setOf(other, newer)))
        assertEquals("pixel", progress.snapshot(other)!!.quizAttempts.single().choice)
        assertEquals("console", progress.snapshot(newer)!!.quizAttempts.single().choice)
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
