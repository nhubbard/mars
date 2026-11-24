package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionExitTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()

  private fun image(
      continuation: String = publish + exit,
      wide: Boolean = false,
      function: String = "li \$v0,7\njrc \$ra",
  ) =
      session().use { machine ->
        val path = directory.resolve("exit.asm")
        val source =
            ".data ${if (wide) "0x100000000" else "0x10010000"}\nresult: .word 0\npath: .asciiz \"input.bin\"\nbuffer: .byte 0\n" +
                ".text\nmain: li \$v0,10\nsyscall\nreturned:\n$continuation\nfunction:\n$function\nfunction_end: nop\n"
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun contract() =
      FunctionCheckCase(
          "caller publication and exit",
          listOf(
              FunctionCallCheck(
                  "function",
                  "returned",
                  emptyMap(),
                  mapOf("v0" to 7L),
                  memory = listOf(FunctionMemoryCheck("result", "00000000")),
              ),
          ),
          exit = FunctionExitCheck(memory = listOf(FunctionMemoryCheck("result", "44332211"))),
      )

  @Test
  fun `caller publishes after function observations and exact memory is checked after actual relocated exit`() {
    val case =
        contract()
            .copy(
                noStores = listOf(FunctionNoStoreCheck("result", 4)),
                accessBounds = listOf(FunctionAccessCheck("result", 4, 0, 0)),
                storeBounds =
                    listOf(
                        FunctionStoreBoundsCheck(
                            FunctionDataAddress("result"),
                            FunctionDataAddress("result", 4),
                        ),
                    ),
                accessPolicies =
                    listOf(
                        FunctionAccessPolicyCheck(
                            FunctionDataAddress("result"),
                            FunctionDataAddress("result", 4),
                        ),
                    ),
                helpers = listOf(FunctionHelperCheck("function", "function_end")),
                noExecution = listOf(FunctionHelperCheck("returned", "function")),
                protectCallerStack = true,
            )
    for (wide in listOf(false, true)) {
      val result =
          PreparedFunctionChecks.evaluate(image(wide = wide), listOf(case), ::session).single()
      assertTrue(result.passed, result.failure)
    }
    val wrong = PreparedFunctionChecks.evaluate(image(exit), listOf(contract()), ::session).single()
    assertFalse(wrong.passed)
  }

  @Test
  fun `termination code output and remaining cumulative budget are checked rather than inferred at return`() {
    for (code in listOf(7, -3)) {
      val image = image(publish + "li \$a0,$code\nli \$v0,17\nsyscall")
      val result =
          PreparedFunctionChecks.evaluate(
                  image,
                  listOf(contract().copy(exit = contract().exit!!.copy(code = code))),
                  ::session,
              )
              .single()
      assertTrue(result.passed, result.failure)
      assertFalse(
          PreparedFunctionChecks.evaluate(image, listOf(contract()), ::session).single().passed,
      )
    }
    val infinite = image("bc returned\nnop")
    val bounded = contract().copy(instructionLimit = 40)
    assertTrue(
        PreparedFunctionChecks.evaluate(infinite, listOf(bounded.copy(exit = null)), ::session)
            .single()
            .passed,
    )
    assertFalse(
        PreparedFunctionChecks.evaluate(infinite, listOf(bounded), ::session).single().passed,
    )
    val prints = image("li \$a0,65\nli \$v0,11\nsyscall\n" + publish + exit)
    assertFalse(
        PreparedFunctionChecks.evaluate(prints, listOf(contract()), ::session).single().passed,
    )
  }

  @Test
  fun `exit data is copied and included in pre-session result bounds`() {
    val expected = contract().exit!!.memory.toMutableList()
    val case = contract().copy(exit = FunctionExitCheck(memory = expected))
    val result =
        PreparedFunctionChecks.evaluate(
                image(),
                listOf(case),
                {
                  expected[0] = FunctionMemoryCheck("result", "00000000")
                  session()
                },
            )
            .single()
    assertTrue(result.passed, result.failure)
    expected += List(64) { FunctionMemoryCheck("result", "00") }
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(),
          listOf(case),
          { error("Validate exit expectations before machine acquisition") },
      )
    }
    assertThrows<IllegalArgumentException> {
      contract().copy(initialMemory = listOf(FunctionMemoryCheck("result", "00".repeat(1_048_576))))
    }
  }

  @Test
  fun `exit continuation cannot hide extra file operations after a marked invocation`() {
    val open = "dla \$a0,path\nli \$a1,0\nli \$v0,13\nsyscall"
    val close = "move \$a0,\$t5\nli \$v0,16\nsyscall"
    val function =
        open +
            "\nmove \$t5,\$v0\nmove \$a0,\$t5\ndla \$a1,buffer\nli \$a2,1\nli \$v0,14\nsyscall\n" +
            close +
            "\nli \$v0,7\njrc \$ra"
    val original = contract()
    val case =
        original.copy(
            calls = listOf(original.calls.single().copy(readFile = true)),
            fileRead = FunctionFileReadCheck("input.bin", "", 1),
            exit = FunctionExitCheck(),
        )
    assertTrue(
        PreparedFunctionChecks.evaluate(image(exit, function = function), listOf(case), ::session)
            .single()
            .passed,
    )
    val extra = open + "\nmove \$t5,\$v0\n" + close + "\n" + exit
    val result =
        PreparedFunctionChecks.evaluate(image(extra, function = function), listOf(case), ::session)
            .single()
    assertFalse(result.passed)
    assertTrue(result.failure!!.contains("no file operations"), result.failure)
  }

  companion object {
    private const val publish = "dla \$t0,result\nli \$t1,0x11223344\nsw \$t1,0(\$t0)\n"
    private const val exit = "li \$v0,10\nsyscall"
  }
}
