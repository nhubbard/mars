package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionFileSystemTest {
    @TempDir lateinit var directory: Path
    private fun session() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private val input = "003180ff72"
    private val old = "6f6c64"
    private val body = $$"""
        .data
        input_path: .asciiz "in.wav"
        output_path: .asciiz "out.wav"
        buffer: .space 4
        .text
        main: li $v0,10
            syscall
        returned: bc main
        pure: move $v0,$zero
            jrc $ra
        copy_file:
            li $v0,13
            dla $a0,input_path
            move $a1,$zero
            syscall
            bltzc $v0,no_handles
            nop
            move $t8,$v0
            li $v0,13
            dla $a0,output_path
            li $a1,1
            syscall
            bltzc $v0,close_input_failure
            nop
            move $t9,$v0
        read_loop:
            li $v0,14
            move $a0,$t8
            dla $a1,buffer
            li $a2,4
            syscall
            bltzc $v0,failed
            nop
            beqzc $v0,finished
            nop
            move $t6,$v0
            dla $t5,buffer
        write_loop:
            li $v0,15
            move $a0,$t9
            move $a1,$t5
            move $a2,$t6
            syscall
            blezc $v0,failed
            nop
            daddu $t5,$t5,$v0
            dsubu $t6,$t6,$v0
            bnezc $t6,write_loop
            nop
            bc read_loop
            nop
        failed:
            li $t7,1
            bc close_both
            nop
        finished:
            move $t7,$zero
        close_both:
            li $v0,16
            move $a0,$t9
            syscall
            li $v0,16
            move $a0,$t8
            syscall
            move $v0,$t7
            jrc $ra
        close_input_failure:
            li $v0,16
            move $a0,$t8
            syscall
        no_handles:
            li $v0,1
            jrc $ra
    """.trimIndent()
    private fun image(source: String = body) = session().use { machine ->
        val path = directory.resolve("files.asm")
        machine.assembleSnapshot(AssemblySourceSnapshot(listOf(path),mapOf(path to source)),null,extended=true,warnings=false)
        machine.captureAssemblyImage {}
    }
    private fun fixture() = FunctionFileSystemCheck(mapOf("in.wav" to input,"out.wav" to old),setOf("out.wav"),
        maxReadSize=2,maxWriteSize=1,firstDescriptor=19)
    private fun call(status: Long = 0, result: String? = input) = FunctionCallCheck("copy_file","returned",emptyMap(),mapOf("v0" to status),
        expectedFiles=mapOf("out.wav" to result))
    private fun case(check: FunctionFileSystemCheck = fixture(), calls: List<FunctionCallCheck> = listOf(call())) =
        FunctionCheckCase("virtual files",calls,fileSystem=check)

    @Test
    fun `short reads and writes use actual descriptors and persist across invocations`() {
        val pure = FunctionCallCheck("pure","returned",emptyMap(),mapOf("v0" to 0L))
        val result = PreparedFunctionChecks.evaluate(image(),listOf(case(calls=listOf(call(),pure,call()))),::session).single()
        assertTrue(result.passed,result.failure)
    }

    @Test
    fun `read failure write failure zero progress and output exhaustion retain their exact prefixes`() {
        val variants = listOf(fixture().copy(failReadAfterBytes=2),fixture().copy(failWriteAfterBytes=2),
            fixture().copy(zeroWriteAfterBytes=2),fixture().copy(maxFileSize=2))
        for (check in variants) {
            val result = PreparedFunctionChecks.evaluate(image(),listOf(case(check,listOf(call(1,input.take(4))))),::session).single()
            assertTrue(result.passed,result.failure)
        }
    }

    @Test
    fun `missing input denied output and missing output expectations do not touch host files`() {
        val missing = fixture().copy(files=mapOf("out.wav" to old))
        val failedOpen = PreparedFunctionChecks.evaluate(image(),listOf(case(missing,listOf(call(1,old)))),::session).single()
        assertTrue(failedOpen.passed,failedOpen.failure)
        val denied = fixture().copy(writablePaths=emptySet())
        val deniedCall = call(1).copy(expectedFiles=emptyMap())
        val deniedResult = PreparedFunctionChecks.evaluate(image(),listOf(case(denied,listOf(deniedCall))),::session).single()
        assertTrue(deniedResult.passed,deniedResult.failure)
        val absent = fixture().copy(files=emptyMap())
        val absentResult = PreparedFunctionChecks.evaluate(image(),listOf(case(absent,listOf(call(1,null)))),::session).single()
        assertTrue(absentResult.passed,absentResult.failure)
        val wrongAbsence = PreparedFunctionChecks.evaluate(image(),listOf(case(calls=listOf(call(result=null)))),::session).single()
        assertFalse(wrongAbsence.passed)

        val inputPath = directory.resolve("host-input.wav")
        val outputPath = directory.resolve("host-output.wav")
        Files.write(inputPath,byteArrayOf(91));Files.write(outputPath,byteArrayOf(92))
        val source = body.replace("in.wav",inputPath.toString()).replace("out.wav",outputPath.toString())
        val check = fixture().copy(files=mapOf(inputPath.toString() to input,outputPath.toString() to old),writablePaths=setOf(outputPath.toString()))
        val result = PreparedFunctionChecks.evaluate(image(source),listOf(case(check,listOf(call().copy(expectedFiles=mapOf(outputPath.toString() to input))))),::session).single()
        assertTrue(result.passed,result.failure)
        assertArrayEquals(byteArrayOf(91),Files.readAllBytes(inputPath));assertArrayEquals(byteArrayOf(92),Files.readAllBytes(outputPath))
    }

    @Test
    fun `leaks wrong descriptors dropped short writes and incorrect bytes fail named checks`() {
        val faults = listOf(
            body.replace("move \$t8,\$v0","li \$t8,3"),
            body.replace("li \$v0,16\n    move \$a0,\$t9\n    syscall", "nop"),
            body.replace("bnezc \$t6,write_loop","nop"),
            body.replace("move \$t6,\$v0","li \$t6,4"),
        )
        for (source in faults) {
            val result = PreparedFunctionChecks.evaluate(image(source),listOf(case()),::session).single()
            assertFalse(result.passed,source)
        }
        assertFalse(PreparedFunctionChecks.evaluate(image(),listOf(case(calls=listOf(call(result="00")))),::session).single().passed)
    }

    @Test
    fun `file operations require an explicit invocation result without retaining event histories`() {
        val noOperations = call().copy(expectedFiles=null)
        val cases = case(calls=listOf(call(),noOperations))
        val result = PreparedFunctionChecks.evaluate(image(),listOf(cases),::session).single()
        assertFalse(result.passed)
        assertTrue(result.failure!!.contains("must perform no file operations"),result.failure)
        val engine = GradingFiles(recordAccesses=false)
        val files = engine.configure(mapOf("in" to byteArrayOf(49)),1,null)
        val descriptor = engine.open("in",0)
        engine.read(descriptor,ByteArray(1),1)
        engine.close(descriptor)
        assertEquals(3L,files.operationCount)
        assertTrue(files.snapshot().isEmpty())
        files.expectAllClosed()
    }

    @Test
    fun `fixture and output containers are detached and revalidated before creating a session`() {
        val inputs = fixture().files.toMutableMap()
        val paths = fixture().writablePaths.toMutableSet()
        val expected = mutableMapOf<String,String?>("out.wav" to input)
        val prepared = case(fixture().copy(files=inputs,writablePaths=paths),listOf(call().copy(expectedFiles=expected)))
        val image = image()
        val result = PreparedFunctionChecks.evaluate(image,listOf(prepared),{
            inputs.clear();paths.clear();expected.clear();session()
        }).single()
        assertTrue(result.passed,result.failure)
        val values = mutableMapOf("in.wav" to input,"out.wav" to old)
        val mutated = case(fixture().copy(files=values))
        values["in.wav"]="not hex"
        assertThrows<IllegalArgumentException> {
            PreparedFunctionChecks.evaluate(image,listOf(mutated),{ error("Validate before session acquisition") })
        }
    }
}
