package edu.missouristate.mars.plugin.learning

import edu.missouristate.mars.cli.grading.ConsoleCheckResult
import edu.missouristate.mars.plugin.learning.MarsDownloadedAssessmentCheckpoint.Feedback
import org.jdom.Verifier
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarsAssessmentFeedbackTest {
    @Test
    fun `inline outcomes explain assembly stale policy budget and public case results`() {
        for ((value, expected) in listOf(Feedback.ASSEMBLY to "Assembly did not succeed", Feedback.STALE to "changed while checking",
            Feedback.POLICY to "blocks independent assessment", Feedback.LIMIT to "limit")) {
            assertTrue(MarsAssessmentFeedback.text(value).contains(expected))
        }
        val passed = MarsAssessmentFeedback.text(Feedback.Checked(listOf(ConsoleCheckResult("input 0", null)), 1, 0))
        assertTrue(passed.contains("1 of 1")); assertFalse(passed.contains("Completed assessments"))
        val failed = MarsAssessmentFeedback.text(Feedback.Checked(listOf(ConsoleCheckResult("<case &>", "Expected <two>\nActual &one")), 2, 3))
        assertTrue(failed.contains("0 of 1")); assertTrue(failed.contains("&lt;case &amp;&gt;"))
        assertTrue(failed.contains("&lt;two&gt;&#10;Actual &amp;one")); assertTrue(failed.contains("2 of 3"))
    }

    @Test
    fun `output controls invalid XML and truncated surrogate pairs remain safe and visible`() {
        val values = listOf(0, 9, 10, 13, 31, 32, 0xd7ff, 0xd800, 0xe000, 0xfffd, 0xfffe, 0x10000, 0x10ffff)
            .joinToString("") { String(Character.toChars(it)) }
        val text = MarsAssessmentFeedback.text(Feedback.Checked(listOf(ConsoleCheckResult("case", values)), 1, 0))
        assertNull(Verifier.checkCharacterData(text))
        for (escape in listOf("\\u0000", "\\u0009", "\\u000d", "\\ud800", "\\ufffe")) assertTrue(text.contains(escape))
        assertTrue(text.contains("&#10;"))
        val long = "x".repeat(4095) + "🙂END"
        val truncated = MarsAssessmentFeedback.text(Feedback.Checked(listOf(ConsoleCheckResult("case", long)), 1, 0))
        assertTrue(truncated.contains("\\ud83d")); assertFalse(truncated.contains("END"))
        assertNull(Verifier.checkCharacterData(truncated))
    }
}
