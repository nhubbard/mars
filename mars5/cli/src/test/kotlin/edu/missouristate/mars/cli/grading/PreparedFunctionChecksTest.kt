package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.MarsComponent
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionChecksTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()

  private fun image(
      body: String,
      target: MarsTarget = MarsTarget.DEFAULT,
      littleEndian: Boolean = true,
  ): AssemblyImage =
      session().use { machine ->
        machine.selectTarget(target)
        machine.memory.byteOrder = littleEndian
        val path = directory.resolve("functions.asm")
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to body)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private val helper =
      $$"""
      reverse_byte:
          andi $t0,$a0,255
          move $v0,$zero
          li $t1,8
      reverse_bit:
          sll $v0,$v0,1
          andi $t2,$t0,1
          or $v0,$v0,$t2
          srl $t0,$t0,1
          addiu $t1,$t1,-1
          bnezc $t1,reverse_bit
          nop
      reverse_byte_return:
          jrc $ra
      """
          .trimIndent()
  private val wrapper =
      $$"""
      daddiu $sp,$sp,-16
      sd $ra,8($sp)
      balc reverse_byte
      xori $v0,$v0,255
      ld $ra,8($sp)
      daddiu $sp,$sp,16
      jrc $ra
      """
          .trimIndent()

  private fun program(body: String) =
      $$".text\nmain: li $v0,10\nsyscall\nafter_invert: bc main\nother_return: bc main\nreverse_and_invert:\n$$body\n$$helper\n"

  private val saved =
      (0..7).associate { "s$it" to (0x1122334400000000L + it * 0x01010101L) } +
          mapOf("fp" to 0x0123456789abcdefL, "gp" to 0x023456789abcdef0L)

  private fun call(input: Int = 22) =
      FunctionCallCheck(
          "reverse_and_invert",
          "after_invert",
          mapOf("a0" to (0x1234567800000000L or input.toLong())),
          mapOf("v0" to (Integer.reverse(input) ushr 24 xor 255).toLong()),
      )

  private fun case(calls: List<FunctionCallCheck> = listOf(call())) =
      FunctionCheckCase(
          "function contract",
          calls,
          initialRegisters = saved,
          preservedRegisters = saved.keys.toList(),
          helpers = listOf(FunctionHelperCheck("reverse_byte", "reverse_byte_return")),
      )

  @Test
  fun `all byte inputs accept a saved-link wrapper and an equivalent tail transfer`() {
    for (body in listOf(wrapper, $$"xori $a0,$a0,255\nbc reverse_byte")) {
      val machines = mutableListOf<MarsContext>()
      val result =
          PreparedFunctionChecks.evaluate(
              image(program(body)),
              listOf(
                  case(
                      (0..255).map {
                        call(it)
                            .copy(returnTo = if (it % 2 == 0) "after_invert" else "other_return")
                      },
                  ),
              ),
              { session().also(machines::add) },
          )
      assertTrue(result.single().passed, result.single().failure)
      assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
      assertThrows<UnsupportedOperationException> { (result as MutableList).clear() }
    }
  }

  @Test
  fun `correct low-byte arithmetic cannot hide a broken call contract`() {
    val faults =
        mapOf(
            "wrong result" to wrapper.replace($$"xori $v0,$v0,255", "nop"),
            "lost saved upper half" to
                wrapper
                    .replace($$"sd $ra,8($sp)", $$"sd $ra,8($sp)\nsw $s0,0($sp)")
                    .replace($$"ld $ra,8($sp)", $$"lw $s0,0($sp)\nld $ra,8($sp)"),
            "wrong restored pointer" to
                wrapper.replace($$"daddiu $sp,$sp,16", $$"daddiu $sp,$sp,32"),
            "caller byte overwritten" to
                wrapper.replace(
                    $$"xori $v0,$v0,255",
                    $$"xori $v0,$v0,255\nli $t2,1\nsb $t2,16($sp)",
                ),
            "noisy callee" to
                wrapper.replace(
                    $$"xori $v0,$v0,255",
                    $$"xori $v0,$v0,255\nmove $a0,$v0\nli $v0,1\nsyscall\nli $v0,151",
                ),
            "early exit" to $$"li $v0,10\nsyscall",
            "no return" to "loop: bc loop",
            "wrong continuation" to wrapper.replace($$"jrc $ra", "bc main"),
            "host file service" to $$"li $v0,13\nsyscall",
        )
    for ((name, body) in faults) {
      val machines = mutableListOf<MarsContext>()
      val result =
          PreparedFunctionChecks.evaluate(
              image(program(body)),
              listOf(case().copy(instructionLimit = 1000)),
              { session().also(machines::add) },
          )
      assertFalse(result.single().passed, name)
      val detail =
          when (name) {
            "wrong result" -> "Expected v0"
            "lost saved upper half" -> "Expected s0"
            "wrong restored pointer" -> "Expected sp"
            "caller byte overwritten" -> "Caller-owned stack bytes"
            "noisy callee" -> "Expected output"
            else -> "Expected execution"
          }
      assertTrue(result.single().failure!!.contains(detail), "$name: ${result.single().failure}")
      assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
    }
    val skipped =
        PreparedFunctionChecks.evaluate(
            image(program($$"li $v0,151\njrc $ra")),
            listOf(case()),
            ::session,
        )
    assertTrue(skipped.single().failure!!.contains("helper interval"))
    val fixedReturn =
        PreparedFunctionChecks.evaluate(
            image(program(wrapper.replace($$"jrc $ra", "bc after_invert"))),
            listOf(
                case(listOf(call(), call(0).copy(returnTo = "other_return")))
                    .copy(instructionLimit = 1000),
            ),
            ::session,
        )
    assertFalse(
        fixedReturn.single().passed,
        "Each invocation must reach its own incoming continuation",
    )
  }

  @Test
  fun `case memories are fresh and image target and byte order survive loading`() {
    val source =
        $$"""
            .data
        count: .word 0x01020304
            .text
        main: nop
        continuation: nop
        next:
            lbu $v0,count
            addiu $v0,$v0,1
            sb $v0,count
            jrc $ra
        """
            .trimIndent()
    val contract =
        FunctionCheckCase(
            "one",
            listOf(FunctionCallCheck("next", "continuation", emptyMap(), mapOf("v0" to 5))),
        )
    for (target in MarsTarget.entries) for (littleEndian in listOf(false, true)) {
      val assembly =
          if (target == MarsTarget.MARS32) source.replace($$"jrc $ra", $$"jr $ra\nnop") else source
      val ordered =
          contract.copy(
              calls =
                  listOf(
                      contract.calls
                          .single()
                          .copy(expectedRegisters = mapOf("v0" to if (littleEndian) 5L else 2L)),
                  ),
          )
      val result =
          PreparedFunctionChecks.evaluate(
              image(assembly, target, littleEndian),
              listOf(ordered, ordered.copy(name = "two")),
              ::session,
          )
      assertTrue(result.all { it.passed }, result.toString())
    }
    val repeated =
        PreparedFunctionChecks.evaluate(
            image(source),
            listOf(contract.copy(calls = contract.calls + contract.calls)),
            ::session,
        )
    assertFalse(repeated.single().passed, "Repeated calls must retain their own case memory")
    val missing =
        PreparedFunctionChecks.evaluate(
            image(source),
            listOf(contract.copy(calls = listOf(contract.calls.single().copy(entry = "missing")))),
            ::session,
        )
    assertTrue(missing.single().failure!!.contains("Missing label"))
  }

  @Test
  fun `explicit saved-link observations see the incoming link installed for each call`() {
    val result =
        PreparedFunctionChecks.evaluate(
            image(program(wrapper)),
            listOf(
                case(listOf(call(), call(129).copy(returnTo = "other_return")))
                    .copy(preservedRegisters = saved.keys.toList() + "ra"),
            ),
            ::session,
        )
    assertTrue(result.single().passed, result.single().failure)
    val badInterval =
        PreparedFunctionChecks.evaluate(
            image(program(wrapper)),
            listOf(
                case()
                    .copy(
                        helpers =
                            listOf(FunctionHelperCheck("reverse_byte_return", "reverse_byte")),
                    ),
            ),
            ::session,
        )
    assertFalse(badInterval.single().passed)
  }

  @Test
  fun `nested caller containers are captured and revalidated before creating machines`() {
    val arguments = mutableMapOf("a0" to 22L)
    val expected = mutableMapOf("v0" to 151L)
    val calls = mutableListOf(call().copy(arguments = arguments, expectedRegisters = expected))
    val registers = saved.toMutableMap()
    val preserved = saved.keys.toMutableList()
    val helpers = mutableListOf(FunctionHelperCheck("reverse_byte", "reverse_byte_return"))
    val cases =
        mutableListOf(
            case(calls)
                .copy(
                    initialRegisters = registers,
                    preservedRegisters = preserved,
                    helpers = helpers,
                ),
        )
    val result =
        PreparedFunctionChecks.evaluate(
            image(program(wrapper)),
            cases,
            {
              arguments.clear()
              expected.clear()
              calls.clear()
              registers.clear()
              preserved.clear()
              helpers.clear()
              cases.clear()
              session()
            },
        )
    assertTrue(result.single().passed, result.single().failure)
    val changed = mutableMapOf("a0" to 1L)
    val invalid = case(listOf(call().copy(arguments = changed)))
    changed[$$"$4"] = 2L
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(program(wrapper)),
          listOf(invalid),
          { error("Must validate before creating") },
      )
    }
  }

  @Test
  fun `invalid public contracts cannot acquire unsupported behavior or unbounded work`() {
    for (change in
        listOf(
            { call().copy(entry = "../outside") },
            { call().copy(entry = "after_invert") },
            { call().copy(returnTo = " ") },
            { call().copy(arguments = mapOf("pc" to 1)) },
            { call().copy(arguments = mapOf("zero" to 1)) },
            { call().copy(arguments = mapOf("sp" to 1)) },
            { call().copy(arguments = mapOf("ra" to 1)) },
            { call().copy(expectedRegisters = emptyMap()) },
            { call().copy(expectedRegisters = mapOf("" to 0)) },
            { call().copy(expectedRegisters = mapOf("$" to 0)) },
            { case().copy(preservedRegisters = listOf("")) },
            { case().copy(preservedRegisters = listOf("$")) },
            { call().copy(expectedRegisters = mapOf("v0" to 1, $$"$2" to 1)) },
            { FunctionHelperCheck("helper", "helper") },
            { case().copy(name = " ") },
            { case().copy(calls = emptyList()) },
            { case().copy(calls = List(513) { call() }) },
            { case().copy(instructionLimit = 0) },
            { case().copy(instructionLimit = 40_000_001) },
            { case().copy(stackAlignment = 3) },
            { case().copy(callerStackBytes = 65537) },
            { case().copy(preservedRegisters = listOf("s0", $$"$16")) },
            { case().copy(helpers = List(33) { FunctionHelperCheck("a$it", "b$it") }) },
        )) assertThrows<IllegalArgumentException> { change() }
    val image = image(program(wrapper))
    val invalidProfiles =
        listOf(
            emptyList(),
            listOf(case(), case()),
            List(257) { case().copy(name = "case$it") },
            List(9) { case(List(512) { call() }).copy(name = "case$it") },
            List(41) { case().copy(name = "case$it", instructionLimit = 1_000_000) },
            List(2) { case().copy(name = "larger case$it", instructionLimit = 20_000_001) },
        )
    for (profile in invalidProfiles) assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image,
          profile,
          { error("Must reject invalid profile first") },
      )
    }
  }

  @Test
  fun `larger function budgets execute beyond one million and remain cumulative across calls`() {
    val image =
        image(
            $$"""
            .text
            main: li $v0,10
                syscall
            returned: bc main
            count_down:
                li $t0,500001
            again:
                daddiu $t0,$t0,-1
                bnezc $t0,again
                li $v0,23
                jrc $ra
            """
                .trimIndent(),
        )
    val call = FunctionCallCheck("count_down", "returned", emptyMap(), mapOf("v0" to 23L))
    val cases =
        listOf(
            FunctionCheckCase("old budget stops", listOf(call), 1_000_000),
            FunctionCheckCase("PPM budget completes", listOf(call), 3_000_000),
            FunctionCheckCase("second call shares exhausted budget", listOf(call, call), 1_500_000),
            FunctionCheckCase("PPM budget completes both calls", listOf(call, call), 3_000_000),
        )
    val results = PreparedFunctionChecks.evaluate(image, cases, ::session)
    assertFalse(results[0].passed)
    assertTrue(results[1].passed, results[1].failure)
    assertFalse(results[2].passed)
    assertTrue(results[3].passed, results[3].failure)
  }

  @Test
  fun `RSA budgets execute beyond six million and retain one cumulative machine budget`() {
    val image =
        image(
            $$"""
            .text
            main: li $v0,10
                syscall
            returned: bc main
            count_down:
                li $t0,3000001
            again:
                daddiu $t0,$t0,-1
                bnezc $t0,again
                li $v0,23
                jrc $ra
            """
                .trimIndent(),
        )
    val call = FunctionCallCheck("count_down", "returned", emptyMap(), mapOf("v0" to 23L))
    val cases =
        listOf(
            FunctionCheckCase("previous ceiling stops", listOf(call), 6_000_000),
            FunctionCheckCase("second invocation shares the budget", listOf(call, call), 7_000_000),
            FunctionCheckCase("repeated arithmetic completes", listOf(call, call), 27_000_000),
        )
    val results = PreparedFunctionChecks.evaluate(image, cases, ::session)
    assertFalse(results[0].passed)
    assertFalse(results[1].passed)
    assertTrue(results[2].passed, results[2].failure)
  }

  @Test
  fun `cancellation between returned calls propagates and disposes the owned machine`() {
    val image = image(program(wrapper))
    assertThrows<CancellationException> {
      PreparedFunctionChecks.evaluate(
          image,
          listOf(case()),
          { error("Cancelled before creating") },
          { throw CancellationException() },
      )
    }
    var machine: MarsContext? = null
    var callsCompleted = false
    assertThrows<CancellationException> {
      PreparedFunctionChecks.evaluate(
          image,
          listOf(case(listOf(call(), call(0))).copy(instructionLimit = 40_000_000)),
          {
            session().also { machine = it }
          },
          {
            val active = machine
            if (active?.program != null && active.registerFile.getLongValueNoNotify(2) == 151L) {
              callsCompleted = true
              throw CancellationException("After first return")
            }
          },
      )
    }
    assertTrue(callsCompleted)
    assertThrows<IllegalStateException> { checkNotNull(machine).claimStoppedSession() }
  }
}
