package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionInitializationTest {
    @TempDir lateinit var directory: Path
    private fun session() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private fun image(body: String, padding: Int = 0, wide: Boolean = false, littleEndian: Boolean = true): AssemblyImage = session().use { machine ->
        machine.memory.byteOrder = littleEndian
        val path = directory.resolve("initialize.asm")
        val source = ".data ${if (wide) "0x100000000" else "0x10010000"}\n.space $padding\n.align 3\n" +
            "output: .dword 123,456\nmarker: .word 0\n" +
            ".text\nmain: li \$v0,10\nsyscall\nreturned: bc main\nother_return: bc main\nfunction:\n$body\nli \$v0,0\njrc \$ra\n"
        machine.assembleSnapshot(AssemblySourceSnapshot(listOf(path), mapOf(path to source)), null, extended = true, warnings = false)
        machine.captureAssemblyImage {}
    }
    private fun range(bytes: Int = 8, offset: Int = 0) = FunctionInitializationCheck(FunctionDataAddress("output", offset), bytes)
    private fun call() = FunctionCallCheck("function", "returned", emptyMap(), mapOf("v0" to 0),
        argumentAddresses = mapOf("a0" to FunctionDataAddress("output")))
    private fun contract() = FunctionCheckCase("initialize output", listOf(call()), initializedOutput = listOf(range()))
    private fun evaluate(body: String, case: FunctionCheckCase = contract()) =
        PreparedFunctionChecks.evaluate(image(body), listOf(case), ::session).single()

    @Test
    fun `different widths orders relocation and byte orders permit initialized reductions`() {
        val bodies = listOf("sd \$zero,0(\$a0)\nld \$t0,0(\$a0)\nsd \$t0,0(\$a0)",
            "sw \$zero,4(\$a0)\nsw \$zero,0(\$a0)\nld \$t0,0(\$a0)",
            (0..7).joinToString("\n") { "sb \$zero,$it(\$a0)" } + "\nld \$t0,0(\$a0)")
        for (body in bodies) for (padding in listOf(0, 264)) for (wide in listOf(false, true)) for (endian in listOf(false, true)) {
            val case = contract().copy(calls = listOf(call(), call().copy(returnTo = "other_return")))
            val result = PreparedFunctionChecks.evaluate(image(body, padding, wide, endian), listOf(case), ::session).single()
            assertTrue(result.passed, result.failure)
        }
    }

    @Test
    fun `old correct bytes and a later repaired result cannot hide an early read`() {
        val result = evaluate("ld \$t0,0(\$a0)\nsd \$zero,0(\$a0)")
        assertFalse(result.passed)
        assertTrue(result.failure!!.contains("read before initialization"), result.failure)
    }

    @Test
    fun `partial stores do not initialize untouched bytes of a wider read`() {
        for (body in listOf("sw \$zero,0(\$a0)\nld \$t0,0(\$a0)", "sb \$zero,0(\$a0)\nlhu \$t0,0(\$a0)")) {
            val result = evaluate(body + "\nsd \$zero,0(\$a0)")
            assertFalse(result.passed)
            assertTrue(result.failure!!.contains("read before initialization"), result.failure)
        }
    }

    @Test
    fun `missing writes fail even when original data equals the expected final result`() {
        for (body in listOf("", "sw \$zero,0(\$a0)")) {
            val result = evaluate(body)
            assertFalse(result.passed)
            assertTrue(result.failure!!.contains("was not initialized"), result.failure)
        }
    }

    @Test
    fun `initialization resets for each call and later cases use fresh machines`() {
        val body = "lw \$t0,marker\nbnezc \$t0,skip\nnop\nli \$t0,1\nsw \$t0,marker\nsd \$zero,0(\$a0)\nskip:"
        val image = image(body)
        val case = contract()
        assertTrue(PreparedFunctionChecks.evaluate(image, listOf(case, case.copy(name = "fresh")), ::session).all { it.passed })
        val result = PreparedFunctionChecks.evaluate(image, listOf(case.copy(calls = listOf(call(), call()))), ::session).single()
        assertFalse(result.passed)
        assertTrue(result.failure!!.contains("Call 2") && result.failure.contains("was not initialized"), result.failure)
        val readOnSecond = body + "\nld \$t0,0(\$a0)"
        assertTrue(evaluate(readOnSecond).passed)
        val second = evaluate(readOnSecond, case.copy(calls = listOf(call(), call())))
        assertTrue(second.failure!!.contains("Call 2") && second.failure.contains("read before initialization"), second.failure)
    }

    @Test
    fun `crossing notices initialize or read the intersecting bytes without prescribing ownership`() {
        val subrange = contract().copy(initializedOutput = listOf(range(2, 3)))
        assertTrue(evaluate("sd \$zero,0(\$a0)\nld \$t0,0(\$a0)", subrange).passed)
        val result = evaluate("lw \$t0,0(\$a0)\nsd \$zero,0(\$a0)", subrange)
        assertFalse(result.passed)
        assertTrue(result.failure!!.contains("byte 0"), result.failure)
        assertTrue(evaluate("sw \$zero,4(\$a0)\nlw \$t0,4(\$a0)", contract().copy(initializedOutput = listOf(range(1, 7)))).passed)
    }

    @Test
    fun `external setup reads unrelated traffic and zero-sized outputs impose no initialization`() {
        val setup = contract().copy(initialMemory = listOf(FunctionMemoryCheck("output", "0000000000000000")))
        assertFalse(evaluate("", setup).passed)
        assertTrue(evaluate("lw \$t0,8(\$a0)\nsw \$t0,8(\$a0)\nsd \$zero,0(\$a0)", setup).passed)
        assertTrue(evaluate("", contract().copy(initializedOutput = listOf(range(0)))).passed)
    }

    @Test
    fun `multiple independent ranges require all bytes`() {
        val case = contract().copy(initializedOutput = listOf(range(4), range(4, 8)))
        assertTrue(evaluate("sw \$zero,0(\$a0)\nsw \$zero,8(\$a0)", case).passed)
        assertFalse(evaluate("sw \$zero,0(\$a0)", case).passed)
    }

    @Test
    fun `mutable range containers are captured and revalidated before any machine opens`() {
        val ranges = mutableListOf(range())
        val case = contract().copy(initializedOutput = ranges)
        val result = PreparedFunctionChecks.evaluate(image(""), listOf(case), { ranges.clear(); session() }).single()
        assertFalse(result.passed)
        ranges.addAll(List(65) { range(0) })
        assertThrows<IllegalArgumentException> {
            PreparedFunctionChecks.evaluate(image(""), listOf(case), { error("Must reject before opening") })
        }
    }

    @Test
    fun `invalid assembled addresses fail as cases and close the owned session`() {
        for (at in listOf(FunctionDataAddress("absent"), FunctionDataAddress("function"))) {
            val machines = mutableListOf<MarsContext>()
            val result = PreparedFunctionChecks.evaluate(image(""), listOf(contract().copy(
                initializedOutput = listOf(FunctionInitializationCheck(at, 8)))), { session().also(machines::add) }).single()
            assertFalse(result.passed)
            assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
        }
    }

    @Test
    fun `closing detaches observers and repeated close is harmless`() {
        session().use { machine ->
            machine.loadAssemblyImage(image("ld \$t0,0(\$a0)")) {}
            AssemblyCase(machine, checkNotNull(machine.program).symbolMap(), ByteArrayOutputStream()).use { execution ->
                val observation = FunctionInitializationObservation(machine.memory, range(0), execution.readDataAddress("output"))
                observation.beginCall(); observation.expectComplete(); observation.close(); observation.close()
                for (first in listOf(-1L, Long.MAX_VALUE)) assertThrows<CaseFailure> {
                    FunctionInitializationObservation(machine.memory, range(), first)
                }
                val nonempty = FunctionInitializationObservation(machine.memory, range(), execution.readDataAddress("output"))
                execution.setRegisterToDataAddress("a0", "output")
                execution.enterFunction("function", "returned"); execution.runUntil("returned")
                assertThrows<CaseFailure> { nonempty.expectComplete() }
                nonempty.beginCall(); nonempty.close()
                execution.enterFunction("function", "returned"); execution.runUntil("returned")
                val failure = assertThrows<CaseFailure> { nonempty.expectComplete() }
                assertTrue(failure.message!!.contains("was not initialized"))
            }
        }
    }

    @Test
    fun `invalid lengths and excessive aggregate storage reject bounded data`() {
        for (length in listOf(-1, 1048577)) assertThrows<IllegalArgumentException> { range(length) }
        assertEquals(1048576, range(1048576).byteLength)
        assertThrows<IllegalArgumentException> { contract().copy(initializedOutput = List(65) { range(0) }) }
        assertThrows<IllegalArgumentException> { contract().copy(initializedOutput = listOf(range(1048576), range(1))) }
    }
}
