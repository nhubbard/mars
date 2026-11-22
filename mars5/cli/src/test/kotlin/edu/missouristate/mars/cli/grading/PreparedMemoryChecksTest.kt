package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.di.DaggerMarsComponent
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedMemoryChecksTest {
    @TempDir lateinit var directory: Path
    private fun session() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private fun image(source:String, target:MarsTarget=MarsTarget.DEFAULT, littleEndian:Boolean=true):AssemblyImage = session().use { machine ->
        machine.selectTarget(target); machine.memory.byteOrder=littleEndian
        val path=directory.resolve("memory.asm")
        machine.assembleSnapshot(AssemblySourceSnapshot(listOf(path),mapOf(path to source)),null,extended=true,warnings=false)
        machine.captureAssemblyImage {}
    }
    private val source = $$"""
        .data
        pixel: .word 0
        guard: .word 0x13579bdf
        .text
        li $v0, 5
        syscall
        la $t0, pixel
        sw $v0, 0($t0)
        li $t1, 0xaa
        sb $t1, 2($t0)
        li $v0, 10
        syscall
    """.trimIndent()
    private fun bytes(word:Int) = (0..3).joinToString("") { ((word ushr (it*8)) and 255).toString(16).padStart(2,'0') }
    private fun cases() = listOf(0,0x336699,0xffffff,0x80007f).map {
        ConsoleCheckCase("color $it","$it\n","",100,
            listOf(ConsoleMemoryCheck(0x10010000,bytes(0xaa0000 or (it and 0xffff))+bytes(0x13579bdf))))
    }

    @Test
    fun `public memory cases accept equivalent computations and preserve adjacent guards`() {
        val alternative=source.replace($$"sw $v0, 0($t0)\nli $t1, 0xaa\nsb $t1, 2($t0)",
            $$"andi $s0, $v0, 0xffff\nli $s1, 0xaa0000\naddu $s0, $s0, $s1\nsw $s0, 0($t0)")
        assertFalse(alternative==source)
        for (target in MarsTarget.entries) for (program in listOf(source,alternative)) {
            val results=PreparedConsoleChecks.evaluate(image(program,target),cases(),::session)
            assertTrue(results.all { it.passed },results.toString())
        }
        for (fault in listOf(source.replace($$"sb $t1, 2",$$"sb $t1, 1"),
            source.replace($$"sb $t1, 2",$$"sw $t1, 2"),source.replace($$"sb $t1, 2",$$"sb $t1, 4"))) {
            val failed=PreparedConsoleChecks.evaluate(image(fault),cases(),::session)
            assertTrue(failed.all { !it.passed })
            assertTrue(failed.all { it.failure!!.contains("Expected byte at 0x1001000") })
        }
    }

    @Test
    fun `memory assertions keep full addresses byte order and observational history`() {
        val code=$$".data 0x100000000\nvalue: .word 0x01020304, 0x80ff7f01\n.text\nli $v0,10\nsyscall\n"
        for (little in listOf(false,true)) {
            val expected=if (little) "04030201017fff80" else "0102030480ff7f01"
            val image=image(code,littleEndian=little)
            assertTrue(PreparedConsoleChecks.evaluate(image,listOf(ConsoleCheckCase("wide","","",100,
                listOf(ConsoleMemoryCheck(0x100000000,expected)))),::session).single().passed)
            session().use { machine ->
                machine.loadAssemblyImage(image) {}
                AssemblyCase(machine,machine.program!!.symbolMap(),ByteArrayOutputStream()).use { case ->
                    val trace=case.traceMemoryAccesses(0x100000000,8)
                    val instructions=case.traceExecution()
                    val before=trace.snapshot(); val undo=machine.backStepper.canBackstep(); val count=instructions.snapshot().instructions
                    case.expectBytes(0x100000001,*expected.chunked(2).map { it.toInt(16) }.drop(1).take(6).toIntArray())
                    assertEquals(before,trace.snapshot()); assertEquals(undo,machine.backStepper.canBackstep())
                    assertEquals(count,instructions.snapshot().instructions)
                    case.expectBytes(Long.MAX_VALUE)
                    assertThrows<IllegalArgumentException> { case.expectBytes(-1L) }
                    assertThrows<IllegalArgumentException> { case.expectBytes(0x100000000,-1) }
                    assertThrows<IllegalArgumentException> { case.expectBytes(Long.MAX_VALUE,0,0) }
                }
            }
        }
    }

    @Test
    fun `missing memory and byte mismatches fail by case without hiding output or termination checks`() {
        val image=image($$".data\n.word 0x01020304\n.text\nli $v0,10\nsyscall\n")
        val results=PreparedConsoleChecks.evaluate(image,listOf(
            ConsoleCheckCase("wrong","","",100,listOf(ConsoleMemoryCheck(0x10010000,"04030001"))),
            ConsoleCheckCase("unmapped","","",100,listOf(ConsoleMemoryCheck(0,"00"))),
            ConsoleCheckCase("correct","","",100,listOf(ConsoleMemoryCheck(0x10010000,"04030201"))),
            ConsoleCheckCase("stdout","","unexpected",100,listOf(ConsoleMemoryCheck(0x10010000,"04030201"))),
        ),::session)
        assertTrue(results[0].failure!!.contains("0x10010002 = 0x00, got 0x02"))
        assertTrue(results[1].failure!!.contains("Cannot inspect byte")); assertTrue(results[2].passed)
        assertTrue(results[3].failure!!.contains("Expected output"))
        val loop=image(".text\nloop: b loop\nnop\n")
        assertTrue(PreparedConsoleChecks.evaluate(loop,listOf(ConsoleCheckCase("limit","","",20,
            listOf(ConsoleMemoryCheck(0x10010000,"00")))),::session).single().failure!!.contains("INSTRUCTION_LIMIT"))
    }

    @Test
    fun `memory definitions snapshot caller lists before creating independent case machines`() {
        val memory=mutableListOf(ConsoleMemoryCheck(0x10010000,"04030201"))
        val cases=listOf(ConsoleCheckCase("first","","",100,memory),ConsoleCheckCase("second","","",100,memory))
        val image=image($$".data\n.word 0x01020304\n.text\nli $v0,10\nsyscall\n")
        val machines=mutableListOf<AutoCloseable>()
        val results=PreparedConsoleChecks.evaluate(image,cases,{ memory.clear(); session().also(machines::add) })
        assertTrue(results.all { it.passed }); assertEquals(2,machines.size)
        val bad=mutableListOf(ConsoleMemoryCheck(0,"00"))
        val mutated=ConsoleCheckCase("mutated","","",100,bad)
        bad+=ConsoleMemoryCheck(0,"ff")
        assertThrows<IllegalArgumentException> { PreparedConsoleChecks.evaluate(image,listOf(mutated),{error("Must validate before creating")}) }
    }

    @Test
    fun `memory ranges reject malformed overflowing overlapping and oversized configurations`() {
        for (hex in listOf("","0","0g"," 00","+1","0\n")) assertThrows<IllegalArgumentException> { ConsoleMemoryCheck(0,hex) }
        assertThrows<IllegalArgumentException> { ConsoleMemoryCheck(-1,"00") }
        assertThrows<IllegalArgumentException> { ConsoleMemoryCheck(Long.MAX_VALUE,"0000") }
        assertEquals(Long.MAX_VALUE,ConsoleMemoryCheck(Long.MAX_VALUE,"fF").address)
        assertThrows<IllegalArgumentException> { ConsoleMemoryCheck(0,"00".repeat(ConsoleMemoryCheck.MAX_BYTES+1)) }
        val range=ConsoleMemoryCheck(0,"00".repeat(ConsoleMemoryCheck.MAX_BYTES))
        assertThrows<IllegalArgumentException> { ConsoleCheckCase("large","","",100,listOf(range,ConsoleMemoryCheck(ConsoleMemoryCheck.MAX_BYTES.toLong(),"00"))) }
        assertThrows<IllegalArgumentException> { ConsoleCheckCase("many","","",100,(0..64).map { ConsoleMemoryCheck(it.toLong(),"00") }) }
        assertThrows<IllegalArgumentException> { ConsoleCheckCase("overlap","","",100,listOf(ConsoleMemoryCheck(3,"0000"),ConsoleMemoryCheck(4,"00"))) }
        ConsoleCheckCase("adjacent","","",100,listOf(ConsoleMemoryCheck(2,"00"),ConsoleMemoryCheck(0,"0000")))
    }
}
