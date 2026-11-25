package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionRepeatedMemoryTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()

  @Test
  fun `repeated fixtures results and per call changes execute as exact bytes in either memory order`() {
    val source =
        $$"""
        .data
        input: .space 8
        output: .space 8
        .text
        main: li $v0,10
            syscall
        returned: bc main
        copy_bytes:
            dla $t0,input
            dla $t1,output
            li $t2,8
        again:
            lbu $t3,0($t0)
            sb $t3,0($t1)
            daddiu $t0,$t0,1
            daddiu $t1,$t1,1
            addiu $t2,$t2,-1
            bnezc $t2,again
            nop
            move $v0,$zero
            jrc $ra
        """
            .trimIndent()
    for (littleEndian in listOf(false, true)) {
      val path = directory.resolve("repeated.asm")
      val image =
          session().use { m ->
            m.memory.byteOrder = littleEndian
            m.assembleSnapshot(
                AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
                null,
                extended = true,
                warnings = false,
            )
            m.captureAssemblyImage {}
          }
      fun call(pattern: String, setup: Boolean = false) =
          FunctionCallCheck(
              "copy_bytes",
              "returned",
              emptyMap(),
              mapOf("v0" to 0L),
              memory = listOf(FunctionMemoryCheck("output", pattern, repeat = 4)),
              setupMemory =
                  if (setup) listOf(FunctionMemoryCheck("input", pattern, repeat = 4))
                  else emptyList(),
          )
      val case =
          FunctionCheckCase(
              "repeated bytes",
              listOf(call("0031"), call("1280", true)),
              initialMemory = listOf(FunctionMemoryCheck("input", "0031", repeat = 4)),
          )
      val result = PreparedFunctionChecks.evaluate(image, listOf(case), ::session).single()
      assertTrue(result.passed, result.failure)
      val wrong = case.copy(calls = listOf(call("0032")))
      assertFalse(PreparedFunctionChecks.evaluate(image, listOf(wrong), ::session).single().passed)
    }
  }
}
