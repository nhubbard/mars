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

class PreparedFunctionAccessPolicyTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()

  private fun image(
      body: String,
      padding: Int = 0,
      wide: Boolean = false,
      reordered: Boolean = false,
  ): AssemblyImage =
      session().use { machine ->
        val path = directory.resolve("policy.asm")
        val objects =
            if (reordered) "output: .word 0,0\ninput: .word 123,456\n"
            else "input: .word 123,456\n.space 8\noutput: .word 0,0\n"
        val source =
            ".data ${if (wide) "0x100000000" else "0x10010000"}\n.space $padding\nbegin: .word 0\n$objects" +
                $$"end: .word 0\nprivate_data: .word 7\n.text\nmain: li $v0,10\nsyscall\nreturned: bc main\nother_return: bc main\nfunction:\n$$body\nli $v0,0\njrc $ra\n"
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun window(label: String, bytes: Int = 8, offset: Int = 0) =
      FunctionDataWindow(FunctionDataAddress(label, offset), bytes)

  private fun policy() =
      FunctionAccessPolicyCheck(
          FunctionDataAddress("begin"),
          FunctionDataAddress("end", 4),
          listOf(window("input"), window("output")),
          listOf(window("output")),
      )

  private fun call() =
      FunctionCallCheck(
          "function",
          "returned",
          emptyMap(),
          mapOf("v0" to 0),
          argumentAddresses =
              mapOf("a0" to FunctionDataAddress("input"), "a1" to FunctionDataAddress("output")),
      )

  private fun contract() =
      FunctionCheckCase(
          "assembled access policy",
          listOf(call()),
          accessPolicies = listOf(policy()),
      )

  private val copy = $$"lw $t0,0($a0)\nsw $t0,0($a1)\nlw $t1,4($a0)\nsw $t1,4($a1)"

  private fun evaluate(body: String, case: FunctionCheckCase = contract()) =
      PreparedFunctionChecks.evaluate(image(body), listOf(case), ::session).single()

  @Test
  fun `explicit history capacities preserve cumulative and invocation scopes without truncating evidence`() {
    val body =
        $$"li $t0,70000\nread_again: lw $t1,0($a0)\ndaddiu $t0,$t0,-1\nbnezc $t0,read_again\nnop"
    val single = contract().copy(instructionLimit = 900_000)
    val legacy = evaluate(body, single)
    assertFalse(legacy.passed)
    assertTrue(legacy.failure!!.contains("65536 notices"), legacy.failure)
    val expanded =
        single.copy(
            calls = listOf(call(), call()),
            accessPolicies = listOf(policy().copy(maxAccesses = 200_000)),
        )
    val accepted = evaluate(body, expanded)
    assertTrue(accepted.passed, accepted.failure)
    val cumulative =
        evaluate(body, expanded.copy(accessPolicies = listOf(policy().copy(maxAccesses = 100_000))))
    assertFalse(cumulative.passed)
    assertTrue(cumulative.failure!!.contains("100000 notices"), cumulative.failure)
    val perCall =
        expanded.copy(
            calls =
                List(2) {
                  call().copy(accessPolicies = listOf(policy().copy(maxAccesses = 100_000)))
                },
        )
    val scoped = evaluate(body, perCall)
    assertTrue(scoped.passed, scoped.failure)
    val tooSmall =
        evaluate(
            body,
            single.copy(
                calls =
                    listOf(
                        call().copy(accessPolicies = listOf(policy().copy(maxAccesses = 60_000))),
                    ),
                accessPolicies = listOf(policy().copy(maxAccesses = 200_000)),
            ),
        )
    assertFalse(tooSmall.passed)
    assertTrue(tooSmall.failure!!.contains("60000 notices"), tooSmall.failure)
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `history capacities are bounded independently and in aggregate before acquiring a session`() {
    for (capacity in listOf(0, -1, 1_000_001)) assertThrows<IllegalArgumentException> {
      policy().copy(maxAccesses = capacity)
    }
    contract().copy(accessPolicies = List(64) { policy() })
    contract().copy(accessPolicies = List(4) { policy().copy(maxAccesses = 1_000_000) })
    assertThrows<IllegalArgumentException> {
      contract().copy(accessPolicies = List(5) { policy().copy(maxAccesses = 1_000_000) })
    }
    assertThrows<IllegalArgumentException> {
      call().copy(accessPolicies = List(5) { policy().copy(maxAccesses = 1_000_000) })
    }
    val policies = mutableListOf(policy())
    val changed = contract().copy(accessPolicies = policies)
    policies.clear()
    policies += List(5) { policy().copy(maxAccesses = 1_000_000) }
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image(copy),
          listOf(changed),
          { error("Validate history bounds before creating a machine") },
      )
    }
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `store-only global policy allows padding reads while named objects retain stricter read windows`() {
    val stores = policy().copy(readable = emptyList(), checkReads = false)
    val named =
        FunctionAccessPolicyCheck(FunctionDataAddress("input"), FunctionDataAddress("input", 8))
    val case = contract().copy(accessPolicies = listOf(stores, named))
    val padding = evaluate($$"lw $t0,8($a0)\nsw $zero,0($a1)", case)
    assertTrue(padding.passed, padding.failure)
    assertFalse(evaluate($$"lw $t0,0($a0)", case).passed)
    assertFalse(evaluate($$"sw $zero,8($a0)", case).passed)
    assertThrows<IllegalArgumentException> { policy().copy(checkReads = false) }
    val noisyReads = stores.copy(maxAccesses = 1)
    assertFalse(
        evaluate(
                $$"lw $t0,8($a0)\nlw $t0,8($a0)",
                contract().copy(accessPolicies = listOf(noisyReads)),
            )
            .passed,
    )
  }

  @Test
  fun `assembled endpoints allow relocation reordering wide pointers and repeated calls`() {
    for (padding in listOf(0, 260)) for (wide in listOf(false, true)) for (reordered in
        listOf(false, true)) {
      val body =
          copy +
              $$"\nlw $t0,0($a1)\ndaddiu $sp,$sp,-16\nsd $ra,0($sp)\nld $ra,0($sp)\ndaddiu $sp,$sp,16"
      val case = contract().copy(calls = listOf(call(), call().copy(returnTo = "other_return")))
      val result =
          PreparedFunctionChecks.evaluate(
                  image(body, padding, wide, reordered),
                  listOf(case),
                  ::session,
              )
              .single()
      assertTrue(result.passed, result.failure)
    }
  }

  @Test
  fun `unnamed padding reads and restored stores fail despite correct final output`() {
    for (prefix in listOf($$"lw $t2,8($a0)", $$"lw $t2,8($a0)\nsw $zero,8($a0)\nsw $t2,8($a0)")) {
      val result = evaluate(prefix + "\n" + copy)
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains("outside its permitted caller windows"), result.failure)
    }
    val restored = evaluate($$"sw $zero,12($a0)\nsw $zero,12($a0)\n$$copy")
    assertTrue(restored.failure!!.contains("store"), restored.failure)
  }

  @Test
  fun `read and write policies are distinct and empty windows permit no accesses`() {
    assertFalse(evaluate($$"sw $zero,0($a0)").passed)
    val writeOnly = contract().copy(accessPolicies = listOf(policy().copy(readable = emptyList())))
    assertTrue(evaluate($$"sw $zero,0($a1)", writeOnly).passed)
    assertFalse(evaluate($$"lw $t0,0($a1)", writeOnly).passed)
    val none =
        contract()
            .copy(
                accessPolicies =
                    listOf(
                        policy()
                            .copy(readable = listOf(window("input", 0)), writable = emptyList()),
                    ),
            )
    assertTrue(evaluate("", none).passed)
    assertFalse(evaluate($$"lw $t0,0($a0)", none).passed)
    assertFalse(evaluate($$"sw $zero,0($a1)", none).passed)
  }

  @Test
  fun `an entire crossing notice must fit in one permitted window`() {
    val case =
        contract()
            .copy(
                accessPolicies =
                    listOf(
                        policy().copy(readable = listOf(window("input", 2), window("input", 2, 2))),
                    ),
            )
    assertFalse(evaluate($$"lw $t0,0($a0)", case).passed)
    assertFalse(
        evaluate(
                $$"lw $t0,4($a0)",
                contract()
                    .copy(
                        accessPolicies =
                            listOf(policy().copy(readable = listOf(window("input", 6)))),
                    ),
            )
            .passed,
    )
    assertFalse(evaluate($$"lw $t0,-4($a0)").passed)
  }

  @Test
  fun `crossing the watched edge still checks the whole access and permits an outside window`() {
    val narrow =
        policy()
            .copy(from = FunctionDataAddress("input", 1), until = FunctionDataAddress("input", 3))
    assertTrue(evaluate($$"lw $t0,0($a0)", contract().copy(accessPolicies = listOf(narrow))).passed)
    assertFalse(
        evaluate(
                $$"lw $t0,0($a0)",
                contract()
                    .copy(
                        accessPolicies =
                            listOf(narrow.copy(readable = listOf(window("input", 2, 1)))),
                    ),
            )
            .passed,
    )
  }

  @Test
  fun `traffic outside watched intervals is unaffected and setup does not count`() {
    val case = contract().copy(initialMemory = listOf(FunctionMemoryCheck("begin", "01020304")))
    assertTrue(evaluate($$"lw $t0,private_data\nsw $t0,private_data\n$$copy", case).passed)
  }

  @Test
  fun `histories cover later calls and every declared watched interval`() {
    val body =
        $$"lw $t0,private_data\naddiu $t0,$t0,1\nsw $t0,private_data\nli $t1,9\nbnec $t0,$t1,skip\nnop\nlw $t2,8($a0)\nskip:\n$$copy"
    assertTrue(evaluate(body).passed)
    assertFalse(evaluate(body, contract().copy(calls = listOf(call(), call()))).passed)
    val extra =
        FunctionAccessPolicyCheck(
            FunctionDataAddress("private_data"),
            FunctionDataAddress("private_data", 4),
        )
    assertFalse(
        evaluate($$"lw $t0,private_data", contract().copy(accessPolicies = listOf(policy(), extra)))
            .passed,
    )
  }

  @Test
  fun `invalid labels or assembled endpoints fail as source contracts and sessions close`() {
    for (bad in
        listOf(
            policy().copy(from = FunctionDataAddress("absent")),
            policy().copy(until = FunctionDataAddress("begin")),
            policy().copy(until = FunctionDataAddress("begin", -4)),
            policy().copy(until = FunctionDataAddress("end", 1048576)),
            policy().copy(readable = listOf(window("function"))),
            policy().copy(writable = listOf(window("absent"))),
        )) {
      val machines = mutableListOf<MarsContext>()
      val result =
          PreparedFunctionChecks.evaluate(
                  image(""),
                  listOf(contract().copy(accessPolicies = listOf(bad))),
                  { session().also(machines::add) },
              )
              .single()
      assertFalse(result.passed)
      assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
    }
    val large = policy().copy(until = FunctionDataAddress("begin", 600000))
    assertFalse(evaluate("", contract().copy(accessPolicies = listOf(large, large))).passed)
  }

  @Test
  fun `caller mutable lists are deeply captured and invalid mutations reject before opening`() {
    val readable = mutableListOf(window("input"))
    val policies = mutableListOf(policy().copy(readable = readable))
    val case = contract().copy(accessPolicies = policies)
    val result =
        PreparedFunctionChecks.evaluate(
                image(copy),
                listOf(case),
                {
                  readable.clear()
                  policies.clear()
                  session()
                },
            )
            .single()
    assertTrue(result.passed, result.failure)
    val invalid = contract().copy(accessPolicies = listOf(policy().copy(readable = readable)))
    readable.addAll(List(65) { window("input", 0) })
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(image(""), listOf(invalid), { error("Must not open") })
    }
  }

  @Test
  fun `bounded traces fail explicitly on overflow instead of dropping forbidden accesses`() {
    val result =
        evaluate(
            $$"li $t0,66000\nloop: lw $t1,0($a0)\naddiu $t0,$t0,-1\nbnezc $t0,loop\nnop",
            contract().copy(instructionLimit = 400000),
        )
    assertFalse(result.passed)
    assertTrue(result.failure!!.contains("trace exceeds 65536 notices"), result.failure)
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `range count and combined sizes are bounded before execution`() {
    assertThrows<IllegalArgumentException> { window("input", -1) }
    assertThrows<IllegalArgumentException> { window("input", 1048577) }
    assertThrows<IllegalArgumentException> {
      policy().copy(readable = List(65) { window("input", 0) })
    }
    assertThrows<IllegalArgumentException> {
      policy().copy(readable = listOf(window("input", 1048576)))
    }
    assertThrows<IllegalArgumentException> {
      contract().copy(accessPolicies = List(65) { policy() })
    }
    val half = policy().copy(readable = listOf(window("input", 600000)), writable = emptyList())
    assertThrows<IllegalArgumentException> { contract().copy(accessPolicies = listOf(half, half)) }
  }
}
