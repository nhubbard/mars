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

class PreparedFunctionMemoryChecksTest {
    @TempDir lateinit var directory: Path
    private fun session() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private val body = $$"""
        li $t0,2
    copy_byte:
        lbu $t1,0($a0)
        sb $t1,0($a1)
        daddiu $a0,$a0,1
        daddiu $a1,$a1,1
        addiu $t0,$t0,-1
        bnezc $t0,copy_byte
        nop
        jrc $ra
    """.trimIndent()
    private fun image(code: String = body, padding: Int = 0, littleEndian: Boolean = true): AssemblyImage = session().use { machine ->
        machine.memory.byteOrder = littleEndian
        val path = directory.resolve("memory-functions.asm")
        val source = ".data\n.space $padding\nsource: .byte 1,2,3,4\ndestination: .byte 5,6,7,8\nguard: .byte 9\n" +
            ".text\nmain: li \$v0,10\nsyscall\nfirst_return: bc main\nsecond_return: bc main\ncopy_two:\n$code\n"
        machine.assembleSnapshot(AssemblySourceSnapshot(listOf(path), mapOf(path to source)), null, extended = true, warnings = false)
        machine.captureAssemblyImage {}
    }
    private fun call() = FunctionCallCheck("copy_two", "first_return", emptyMap(), emptyMap(),
        argumentAddresses = mapOf("a0" to FunctionDataAddress("source", 1), "a1" to FunctionDataAddress("destination", 1)),
        memory = listOf(FunctionMemoryCheck("source", "110080ff"), FunctionMemoryCheck("destination", "5a00805d"),
            FunctionMemoryCheck("guard", "09")))
    private fun case() = FunctionCheckCase("copy bytes into a prefilled object", listOf(call()),
        initialRegisters = mapOf("s0" to 0x1122334455667788), preservedRegisters = listOf("s0"),
        initialMemory = listOf(FunctionMemoryCheck("source", "110080ff"), FunctionMemoryCheck("destination", "5a5b5c5d")))

    @Test
    fun `relocated data addresses and unaligned byte setup work in either image byte order`() {
        for (padding in listOf(0, 259)) for (littleEndian in listOf(false, true)) {
            val contract = case().copy(calls = listOf(call(), call().copy(returnTo = "second_return")))
            val results = PreparedFunctionChecks.evaluate(image(padding = padding, littleEndian = littleEndian),
                listOf(contract, contract.copy(name = "fresh machine")), ::session)
            assertTrue(results.all { it.passed }, results.toString())
        }
        val single = case().copy(initialMemory = listOf(FunctionMemoryCheck("source", "00", 1),
            FunctionMemoryCheck("destination", "5a5b5c5d")), calls = listOf(call().copy(memory = listOf(
                FunctionMemoryCheck("source", "01000304"), FunctionMemoryCheck("destination", "5a00035d")))))
        assertTrue(PreparedFunctionChecks.evaluate(image(), listOf(single), ::session).single().passed)
    }

    @Test
    fun `memory checks reject skipped zero stores wrong stride and source corruption`() {
        val faults = listOf(
            body.replace("sb \$t1,0(\$a1)", "beqzc \$t1,skip_store\nnop\nsb \$t1,0(\$a1)\nskip_store:"),
            body.replace("daddiu \$a1,\$a1,1", "daddiu \$a1,\$a1,2"),
            body.replace("sb \$t1,0(\$a1)", "sb \$t1,0(\$a1)\nsb \$zero,0(\$a0)"),
            body.replace("jrc \$ra", "sb \$zero,guard\njrc \$ra"),
        )
        for (fault in faults) {
            val result = PreparedFunctionChecks.evaluate(image(fault), listOf(case()), ::session).single()
            assertFalse(result.passed)
            assertTrue(result.failure!!.contains("Expected byte"), result.failure)
        }
        val wrong = call().copy(argumentAddresses = mapOf("a0" to FunctionDataAddress("source"), "a1" to FunctionDataAddress("destination")))
        assertFalse(PreparedFunctionChecks.evaluate(image(), listOf(case().copy(calls = listOf(wrong))), ::session).single().passed)
    }

    @Test
    fun `invalid submitted data labels fail a named case and close its machine`() {
        for (label in listOf("missing", "copy_two")) {
            val machines = mutableListOf<MarsContext>()
            val result = PreparedFunctionChecks.evaluate(image(), listOf(case().copy(
                initialMemory = listOf(FunctionMemoryCheck(label, "01")))), { session().also(machines::add) }).single()
            assertFalse(result.passed)
            assertTrue(result.failure!!.contains(if (label == "missing") "Missing label" else "Expected a data label"), result.failure)
            assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
            val argument = call().copy(argumentAddresses = mapOf("a0" to FunctionDataAddress(label)))
            assertFalse(PreparedFunctionChecks.evaluate(image(), listOf(case().copy(calls = listOf(argument))), ::session).single().passed)
            val expectation = call().copy(memory = listOf(FunctionMemoryCheck(label, "01")))
            assertFalse(PreparedFunctionChecks.evaluate(image(), listOf(case().copy(calls = listOf(expectation))), ::session).single().passed)
        }
        val outside = FunctionMemoryCheck("source", "01", -1048576)
        assertFalse(PreparedFunctionChecks.evaluate(image(), listOf(case().copy(initialMemory = listOf(outside))), ::session).single().passed)
    }

    @Test
    fun `nested memory and address containers are captured and revalidated before opening sessions`() {
        val addresses = call().argumentAddresses.toMutableMap()
        val memory = call().memory.toMutableList()
        val initial = case().initialMemory.toMutableList()
        val contract = case().copy(calls = listOf(call().copy(argumentAddresses = addresses, memory = memory)), initialMemory = initial)
        val result = PreparedFunctionChecks.evaluate(image(), listOf(contract), {
            addresses.clear(); memory.clear(); initial.clear(); session()
        }).single()
        assertTrue(result.passed, result.failure)
        val changed = mutableMapOf("a0" to FunctionDataAddress("source"))
        val invalid = case().copy(calls = listOf(call().copy(argumentAddresses = changed)))
        changed["\$4"] = FunctionDataAddress("destination")
        assertThrows<IllegalArgumentException> { PreparedFunctionChecks.evaluate(image(), listOf(invalid), { error("Validate before creating") }) }
        val expanded = mutableListOf(FunctionMemoryCheck("source", "01"))
        val invalidMemory = case().copy(initialMemory = expanded)
        repeat(64) { expanded += FunctionMemoryCheck("source", "01") }
        assertThrows<IllegalArgumentException> { PreparedFunctionChecks.evaluate(image(), listOf(invalidMemory), { error("Validate before creating") }) }
    }

    @Test
    fun `address and memory contracts are bounded and cannot overwrite caller setup registers`() {
        for (change in listOf<() -> Any>(
            { FunctionDataAddress("../file") }, { FunctionDataAddress("source", 1048577) },
            { FunctionDataAddress("source", -1048577) }, { FunctionMemoryCheck("source", "") },
            { FunctionMemoryCheck("source", "0") }, { FunctionMemoryCheck("source", "gg") },
            { FunctionMemoryCheck("source", "00".repeat(1048577)) },
            { call().copy(arguments = mapOf("a0" to 1)) },
            { call().copy(arguments = mapOf("\$4" to 1)) },
            { call().copy(argumentAddresses = mapOf("sp" to FunctionDataAddress("source"))) },
            { call().copy(argumentAddresses = mapOf("ra" to FunctionDataAddress("source"))) },
            { call().copy(argumentAddresses = mapOf("zero" to FunctionDataAddress("source"))) },
            { call().copy(argumentAddresses = mapOf("pc" to FunctionDataAddress("source"))) },
            { call().copy(memory = emptyList()) },
            { call().copy(memory = List(65) { FunctionMemoryCheck("source", "01") }) },
            { case().copy(initialMemory = List(65) { FunctionMemoryCheck("source", "01") }) },
            { case().copy(initialMemory = listOf(FunctionMemoryCheck("source", "00".repeat(600000))),
                calls = listOf(call().copy(memory = listOf(FunctionMemoryCheck("destination", "00".repeat(600000)))))) },
        )) assertThrows<IllegalArgumentException> { change() }
    }
}
