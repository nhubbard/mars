package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionCallAccessPolicyTest {
    @TempDir lateinit var directory: Path
    private fun session() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private fun image(body: String, wide: Boolean = false, padding: Int = 0): AssemblyImage = session().use { machine ->
        val path = directory.resolve("call-policy.asm")
        val source = ".data ${if (wide) "0x100010000" else "0x10010000"}\n.space $padding\nbegin: .word 0\n" +
            "input: .word 11,22\n.space 4\noutput: .word 0,0\nend: .word 0\n.text\nmain: li \$v0,10\nsyscall\n" +
            "returned: nop\nother_return: nop\nfunction:\n$body\nli \$v0,0\njrc \$ra\n"
        machine.assembleSnapshot(AssemblySourceSnapshot(listOf(path), mapOf(path to source)), null, extended = true, warnings = false)
        machine.captureAssemblyImage {}
    }
    private fun window(label: String, offset: Int, length: Int) = FunctionDataWindow(FunctionDataAddress(label, offset), length)
    private fun policy(offset: Int = 0, length: Int = 8) = FunctionAccessPolicyCheck(
        FunctionDataAddress("begin"), FunctionDataAddress("end", 4),
        listOf(window("input", offset, length), window("output", offset, length)), listOf(window("output", offset, length)))
    private fun call(index: Int, length: Int = 4, scoped: Boolean = true) = FunctionCallCheck(
        "function", if (index == 0) "returned" else "other_return", mapOf("a2" to length.toLong(), "a3" to index.toLong()), mapOf("v0" to 0L),
        argumentAddresses = mapOf("a0" to FunctionDataAddress("input", index * 4), "a1" to FunctionDataAddress("output", index * 4)),
        memory = listOf(FunctionMemoryCheck("output", if (index == 0 || length == 0) "0b00000000000000" else "0b00000016000000")),
        accessPolicies = if (scoped) listOf(policy(index * 4, length)) else emptyList())
    private fun contract(scoped: Boolean = true, secondLength: Int = 4) = FunctionCheckCase("per-call slices",
        listOf(call(0, scoped = scoped), call(1, secondLength, scoped)), accessPolicies = listOf(policy()))
    private val copy = "lw \$t0,0(\$a0)\nsw \$t0,0(\$a1)"
    private fun evaluate(body: String, case: FunctionCheckCase = contract()) = PreparedFunctionChecks.evaluate(image(body), listOf(case), ::session).single()

    @Test
    fun `each invocation owns its slice and prior histories do not observe a later call`() {
        for (wide in listOf(false, true)) for (padding in listOf(0, 260)) {
            val body = copy + "\ndaddiu \$sp,\$sp,-16\nsd \$ra,0(\$sp)\nld \$ra,0(\$sp)\ndaddiu \$sp,\$sp,16"
            val result = PreparedFunctionChecks.evaluate(image(body, wide, padding), listOf(contract()), ::session).single()
            assertTrue(result.passed, result.failure)
        }
    }

    @Test
    fun `a later call cannot read the preceding slice even when the whole case permits it`() {
        val body = "beqzc \$a3,skip\nnop\nlw \$t1,-4(\$a0)\nskip:\n$copy"
        assertTrue(evaluate(body, contract(scoped = false)).passed)
        val result = evaluate(body)
        assertFalse(result.passed)
        val failure = checkNotNull(result.failure)
        assertTrue(failure.contains("Call 2") && failure.contains("read") &&
            failure.contains("outside its permitted caller windows"), failure)
    }

    @Test
    fun `same-value stores to the preceding output fail within the later call`() {
        val body = "beqzc \$a3,skip\nnop\nli \$t1,11\nsw \$t1,-4(\$a1)\nskip:\n$copy"
        assertTrue(evaluate(body, contract(scoped = false)).passed)
        val result = evaluate(body)
        assertFalse(result.passed)
        val failure = checkNotNull(result.failure)
        assertTrue(failure.contains("Call 2") && failure.contains("store"), failure)
    }

    @Test
    fun `empty work after nonempty work permits no buffer reads or writes`() {
        val correct = "beqzc \$a2,finish\nnop\n$copy\nfinish:"
        assertTrue(evaluate(correct, contract(secondLength = 0)).passed)
        val lateTest = "lw \$t0,0(\$a0)\nbeqzc \$a2,finish\nnop\nsw \$t0,0(\$a1)\nfinish:"
        assertTrue(evaluate(lateTest, contract(scoped = false, secondLength = 0)).passed)
        val result = evaluate(lateTest, contract(secondLength = 0))
        assertFalse(result.passed)
        val failure = checkNotNull(result.failure)
        assertTrue(failure.contains("Call 2") && failure.contains("read"), failure)
    }

    @Test
    fun `call-only policies still separate read and write permissions and full access widths`() {
        val first = call(0).copy(accessPolicies = listOf(policy(0, 4).copy(readable = listOf(window("input", 0, 4)))))
        val case = contract().copy(calls = listOf(first), accessPolicies = emptyList())
        assertTrue(evaluate(copy, case).passed)
        assertFalse(evaluate("sw \$zero,0(\$a0)\n$copy", case).passed)
        assertFalse(evaluate("lw \$t1,0(\$a1)\n$copy", case).passed)
        val crossing = first.copy(accessPolicies = listOf(policy(0, 4).copy(readable = listOf(window("input", 0, 2), window("input", 2, 2)))))
        assertFalse(evaluate(copy, case.copy(calls = listOf(crossing))).passed)
    }

    @Test
    fun `nested caller-owned policy lists are captured before opening a session`() {
        val readable = mutableListOf(window("input", 0, 4))
        val policies = mutableListOf(policy(0, 4).copy(readable = readable))
        val first = call(0).copy(accessPolicies = policies)
        val result = PreparedFunctionChecks.evaluate(image(copy), listOf(contract().copy(calls = listOf(first))), {
            readable.clear(); policies.clear(); session()
        }).single()
        assertTrue(result.passed, result.failure)
        val invalid = call(0).copy(accessPolicies = listOf(policy(0, 4).copy(readable = readable)))
        readable.addAll(List(65) { window("input", 0, 0) })
        assertThrows<IllegalArgumentException> {
            PreparedFunctionChecks.evaluate(image(copy), listOf(contract().copy(calls = listOf(invalid))), { error("Must not open") })
        }
    }

    @Test
    fun `invalid per-call endpoints report the call and close their session`() {
        val large = policy().copy(until = FunctionDataAddress("begin", 600000))
        for (bad in listOf(listOf(policy().copy(from = FunctionDataAddress("absent"))),
            listOf(policy().copy(until = FunctionDataAddress("begin"))), listOf(large, large))) {
            val machines = mutableListOf<MarsContext>()
            val case = contract().copy(calls = listOf(call(0).copy(accessPolicies = bad)))
            val result = PreparedFunctionChecks.evaluate(image(copy), listOf(case), { session().also(machines::add) }).single()
            assertFalse(result.passed)
            assertTrue(result.failure!!.startsWith("Call 1"), result.failure)
            assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
        }
    }

    @Test
    fun `cancellation after a checked call closes its observations and machine`() {
        val machines = mutableListOf<MarsContext>()
        var checks = 0
        assertThrows<CancellationException> {
            PreparedFunctionChecks.evaluate(image(copy), listOf(contract()), { session().also(machines::add) }, {
                if (++checks == 3) throw CancellationException("after first call")
            })
        }
        assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
    }

    @Test
    fun `call policy counts and combined permitted lengths are bounded before execution`() {
        assertThrows<IllegalArgumentException> { call(0).copy(accessPolicies = List(65) { policy() }) }
        val half = policy().copy(readable = listOf(window("input", 0, 600000)), writable = emptyList())
        assertThrows<IllegalArgumentException> { call(0).copy(accessPolicies = listOf(half, half)) }
    }
}
