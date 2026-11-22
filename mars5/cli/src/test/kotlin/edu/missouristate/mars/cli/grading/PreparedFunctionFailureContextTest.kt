package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionFailureContextTest {
    @TempDir lateinit var directory: Path
    private fun session() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private fun image() = session().use { machine ->
        val path = directory.resolve("context.asm")
        val source = ".text\nmain: li \$v0,10\nsyscall\nreturned: bc main\nother_return: bc main\n" +
            "echo:\nmove \$v0,\$a0\nli \$a0,999\njrc \$ra\n"
        machine.assembleSnapshot(AssemblySourceSnapshot(listOf(path), mapOf(path to source)), null, extended = true, warnings = false)
        machine.captureAssemblyImage {}
    }

    @Test
    fun `failure names the exact call and supplied scalar values rather than clobbered registers`() {
        val first = FunctionCallCheck("echo", "returned", mapOf("a0" to 7), mapOf("v0" to 7))
        val second = FunctionCallCheck("echo", "other_return", linkedMapOf("a2" to 128, "a0" to -1, "a1" to 0), mapOf("v0" to 99))
        val results = PreparedFunctionChecks.evaluate(image(), listOf(
            FunctionCheckCase("two calls", listOf(first, second)),
            FunctionCheckCase("fresh correct case", listOf(second.copy(expectedRegisters = mapOf("v0" to -1)))),
        ), ::session)
        assertFalse(results[0].passed)
        assertTrue(results[0].failure!!.startsWith("Call 2 to echo returning at other_return (a0=-1, a1=0, a2=128): "), results[0].failure)
        assertTrue(results[0].failure!!.contains("99"), results[0].failure)
        assertTrue(results[1].passed, results[1].failure)
    }

    @Test
    fun `empty argument maps retain call identity and missing entry failures remain attributable`() {
        for (entry in listOf("echo", "missing")) {
            val call = FunctionCallCheck(entry, "returned", emptyMap(), mapOf("v0" to 1))
            val result = PreparedFunctionChecks.evaluate(image(), listOf(FunctionCheckCase("no arguments", listOf(call))), ::session).single()
            assertFalse(result.passed)
            assertTrue(result.failure!!.startsWith("Call 1 to $entry returning at returned: "), result.failure)
            assertFalse(result.failure.contains("()"))
        }
    }

    @Test
    fun `cancellation inside call execution checks propagates without becoming a grading failure`() {
        val call = FunctionCallCheck("echo", "returned", mapOf("a0" to 1), mapOf("v0" to 1))
        var active: MarsContext? = null
        val cancelled = assertThrows<CancellationException> {
            PreparedFunctionChecks.evaluate(image(), listOf(FunctionCheckCase("cancel", listOf(call))),
                { session().also { active = it } }) {
                if (active?.registerFile?.getLongValueNoNotify(4) == 999L) throw CancellationException("Stop checking")
            }
        }
        assertEquals("Stop checking", cancelled.message)
    }
}
