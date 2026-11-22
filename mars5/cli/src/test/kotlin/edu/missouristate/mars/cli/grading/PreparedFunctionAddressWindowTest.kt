package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionAddressWindowTest {
    @TempDir lateinit var directory: Path
    private fun session() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private fun image(body: String, wide: Boolean = false, padding: Int = 0, reordered: Boolean = false, littleEndian: Boolean = true): AssemblyImage = session().use { machine ->
        machine.memory.byteOrder = littleEndian
        val path = directory.resolve("address-window.asm")
        val output = "output: .space 8\n"
        val data = "input: .space 16\npadding: .space 8\n" + (if (reordered) "" else output) + "last: .word 99\n" + (if (reordered) output else "")
        val source = ".data ${if (wide) "0x100000000" else "0x10010000"}\n.space $padding\n.align 3\n$data" +
            ".text\nmain: li \$v0,10\nsyscall\nreturned: nop\nother_return: bc main\nfunction:\n$body\nli \$v0,0\njrc \$ra\n"
        machine.assembleSnapshot(AssemblySourceSnapshot(listOf(path), mapOf(path to source)), null, extended = true, warnings = false)
        machine.captureAssemblyImage {}
    }
    private fun window(permitted: FunctionDataAddress? = FunctionDataAddress("output"), length: Int = 8) =
        FunctionStoreBoundsCheck(FunctionDataAddress("input"), FunctionDataAddress("last", 4), permitted, length)
    private fun call() = FunctionCallCheck("function", "returned", emptyMap(), mapOf("v0" to 0),
        argumentAddresses = mapOf("a0" to FunctionDataAddress("input", 4), "a2" to FunctionDataAddress("output")),
        expectedAddresses = mapOf("v1" to FunctionDataAddress("input", 5)))
    private fun contract() = FunctionCheckCase("borrowed pointer and selected stores", listOf(call()), storeBounds = listOf(window()))
    private fun evaluate(body: String, case: FunctionCheckCase = contract()) =
        PreparedFunctionChecks.evaluate(image(body), listOf(case), ::session).single()
    private val valid = "lbu \$t0,0(\$a0)\nsw \$t0,0(\$a2)\nsw \$zero,4(\$a2)\ndaddiu \$v1,\$a0,1"

    @Test
    fun `assembled pointer results and selected stores survive relocation wide addresses repeat calls and reordered records`() {
        for (wide in listOf(false, true)) for (padding in listOf(0, 264)) for (reordered in listOf(false, true)) {
            val case = contract().copy(calls = listOf(call(), call().copy(returnTo = "other_return")))
            val results = PreparedFunctionChecks.evaluate(image(valid, wide, padding, reordered), listOf(case, case.copy(name = "fresh")), ::session)
            assertTrue(results.all { it.passed }, results.toString())
        }
    }

    @Test
    fun `wrong or truncated borrowed pointers fail including a pointer-only result contract`() {
        val onlyPointer = contract().copy(calls = listOf(call().copy(expectedRegisters = emptyMap())))
        assertTrue(evaluate(valid, onlyPointer).passed)
        for (body in listOf("move \$v1,\$a0", "li \$v1,0")) {
            val result = evaluate(body, onlyPointer)
            assertFalse(result.passed)
            assertTrue(result.failure!!.contains("Expected v1"), result.failure)
        }
        val narrowed = PreparedFunctionChecks.evaluate(image("addiu \$v1,\$a0,1", wide = true), listOf(onlyPointer), ::session).single()
        assertFalse(narrowed.passed)
    }

    @Test
    fun `store windows allow reads throughout the watched data while rejecting padding and restored stores`() {
        assertTrue(evaluate("lw \$t0,padding\n$valid").passed)
        for (prefix in listOf("sw \$zero,padding", "lw \$t0,padding\nli \$t1,99\nsw \$t1,padding\nsw \$t0,padding", "sw \$zero,last")) {
            val result = evaluate("$prefix\n$valid")
            assertFalse(result.passed)
            assertTrue(result.failure!!.contains("Expected stores within 8 bytes"), result.failure)
        }
    }

    @Test
    fun `zero permitted stores reject same value writes and crossing stores must fit wholly`() {
        val none = contract().copy(storeBounds = listOf(window(null, 0)))
        assertTrue(evaluate("lbu \$t0,0(\$a0)\ndaddiu \$v1,\$a0,1", none).passed)
        assertFalse(evaluate(valid, none).passed)
        for (body in listOf("sw \$zero,-4(\$a2)", "sd \$zero,4(\$a2)", "sw \$zero,8(\$a2)")) {
            val result = evaluate("$body\ndaddiu \$v1,\$a0,1")
            assertFalse(result.passed)
        }
        val emptyAtOutput = contract().copy(storeBounds = listOf(window(FunctionDataAddress("output"), 0)))
        assertFalse(evaluate(valid, emptyAtOutput).passed)
    }

    @Test
    fun `caller mutation cannot replace captured address expectations or remove watched windows`() {
        val expected = mutableMapOf("v1" to FunctionDataAddress("input", 5))
        val bounds = mutableListOf(window())
        val case = contract().copy(calls = listOf(call().copy(expectedAddresses = expected)), storeBounds = bounds)
        val result = PreparedFunctionChecks.evaluate(image("sw \$zero,padding\ndaddiu \$v1,\$a0,1"), listOf(case), {
            expected.clear(); bounds.clear(); session()
        }).single()
        assertFalse(result.passed)
        assertTrue(result.failure!!.contains("Expected stores within"), result.failure)
        val returned = mutableMapOf("v1" to FunctionDataAddress("input", 5))
        val pointerCase = contract().copy(calls = listOf(call().copy(expectedAddresses = returned)))
        val pointerResult = PreparedFunctionChecks.evaluate(image("move \$v1,\$a0"), listOf(pointerCase), {
            returned["v1"] = FunctionDataAddress("input", 4); session()
        }).single()
        assertFalse(pointerResult.passed)
        assertTrue(pointerResult.failure!!.contains("Expected v1"), pointerResult.failure)
        val mutable = mutableMapOf("v1" to FunctionDataAddress("input"))
        val invalid = contract().copy(calls = listOf(call().copy(expectedAddresses = mutable)))
        mutable["3"] = FunctionDataAddress("output")
        assertThrows<IllegalArgumentException> { PreparedFunctionChecks.evaluate(image(valid), listOf(invalid), { error("Validate before opening") }) }
    }

    @Test
    fun `missing reversed oversized and excessive assembled windows are source failures and sessions close`() {
        val invalid = listOf(
            window().copy(from = FunctionDataAddress("absent")), window().copy(until = FunctionDataAddress("function")),
            window().copy(until = FunctionDataAddress("input")), window().copy(from = FunctionDataAddress("last"), until = FunctionDataAddress("input")),
            window().copy(until = FunctionDataAddress("last", 1048576)), window().copy(permittedFrom = FunctionDataAddress("absent")),
        )
        for (bound in invalid) {
            val machines = mutableListOf<MarsContext>()
            val result = PreparedFunctionChecks.evaluate(image(valid), listOf(contract().copy(storeBounds = listOf(bound))),
                { session().also(machines::add) }).single()
            assertFalse(result.passed)
            assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
        }
        val large = window().copy(until = FunctionDataAddress("input", 600000))
        assertFalse(evaluate(valid, contract().copy(storeBounds = listOf(large, large))).passed)
    }

    @Test
    fun `stored full pointers use the image byte order across relocation and wide data`() {
        val pointer = FunctionAddressMemoryCheck(FunctionDataAddress("output"), FunctionDataAddress("input", 5))
        val case = contract().copy(calls = listOf(call().copy(expectedRegisters = emptyMap(), expectedAddresses = emptyMap(), addressMemory = listOf(pointer))))
        for (wide in listOf(false, true)) for (endian in listOf(false, true)) for (padding in listOf(0, 264)) {
            val body = "daddiu \$v1,\$a0,1\nsd \$v1,0(\$a2)"
            val result = PreparedFunctionChecks.evaluate(image(body, wide, padding, littleEndian = endian), listOf(case), ::session).single()
            assertTrue(result.passed, result.failure)
            val truncated = PreparedFunctionChecks.evaluate(image("daddiu \$v1,\$a0,1\nsw \$v1,0(\$a2)", wide = true, littleEndian = endian), listOf(case), ::session).single()
            assertFalse(truncated.passed)
        }
    }

    @Test
    fun `stored pointer checks are captured reject missing labels and count toward memory result bounds`() {
        val checks = mutableListOf(FunctionAddressMemoryCheck(FunctionDataAddress("output"), FunctionDataAddress("input", 5)))
        val case = contract().copy(calls = listOf(call().copy(addressMemory = checks)))
        val failed = PreparedFunctionChecks.evaluate(image("daddiu \$v1,\$a0,1\nsd \$zero,0(\$a2)"), listOf(case), {
            checks.clear(); session()
        }).single()
        assertFalse(failed.passed)
        for (check in listOf(
            FunctionAddressMemoryCheck(FunctionDataAddress("absent"), FunctionDataAddress("input")),
            FunctionAddressMemoryCheck(FunctionDataAddress("output"), FunctionDataAddress("function")),
        )) assertFalse(evaluate(valid, contract().copy(calls = listOf(call().copy(addressMemory = listOf(check))))).passed)
        val check = FunctionAddressMemoryCheck(FunctionDataAddress("output"), FunctionDataAddress("input"))
        assertThrows<IllegalArgumentException> { call().copy(addressMemory = List(65) { check }) }
        assertThrows<IllegalArgumentException> { call().copy(memory = listOf(FunctionMemoryCheck("input", "00".repeat(1048576))), addressMemory = listOf(check)) }
    }

    @Test
    fun `a hardcoded continuation cannot fall through a forbidden interval to the requested stop`() {
        val body = "daddiu \$v1,\$a0,1\nla \$t0,returned\njrc \$t0"
        val calls = listOf(call(), call().copy(returnTo = "other_return"))
        assertTrue(evaluate(body, contract().copy(calls = calls)).passed)
        val checked = contract().copy(calls = calls, noExecution = listOf(FunctionHelperCheck("returned", "other_return")))
        val failed = evaluate(body, checked)
        assertFalse(failed.passed)
        assertTrue(failed.failure!!.contains("Call 2") && failed.failure.contains("Forbidden instruction interval"), failed.failure)
        assertTrue(evaluate(valid, checked).passed)
        val invalid = checked.copy(noExecution = listOf(FunctionHelperCheck("other_return", "returned")))
        assertFalse(evaluate(valid, invalid).passed)
        assertThrows<IllegalArgumentException> { checked.copy(noExecution = List(33) { FunctionHelperCheck("returned", "other_return") }) }
        assertThrows<IllegalArgumentException> { checked.copy(noExecution = List(2) { FunctionHelperCheck("returned", "other_return") }) }
    }

    @Test
    fun `unknown expected address labels fail as source checks and no-result register conflicts reject early`() {
        for (label in listOf("absent", "function")) {
            val result = evaluate(valid, contract().copy(calls = listOf(call().copy(expectedAddresses = mapOf("v1" to FunctionDataAddress(label))))))
            assertFalse(result.passed)
        }
        for (invalid in listOf<() -> Any>(
            { call().copy(expectedAddresses = mapOf("v0" to FunctionDataAddress("input"))) },
            { call().copy(expectedAddresses = mapOf("2" to FunctionDataAddress("input"))) },
            { call().copy(expectedAddresses = mapOf("no-register" to FunctionDataAddress("input"))) },
            { call().copy(expectedRegisters = emptyMap(), expectedAddresses = emptyMap()) },
            { window(null, 1) }, { window(length = -1) }, { window(length = 1048577) },
            { contract().copy(storeBounds = List(65) { window() }) },
        )) assertThrows<IllegalArgumentException> { invalid() }
    }
}
