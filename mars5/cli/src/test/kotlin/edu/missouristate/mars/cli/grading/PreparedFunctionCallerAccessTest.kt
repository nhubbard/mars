package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.MarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionCallerAccessTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      MarsComponent.build {
            guiProxy(HeadlessGuiProxy())
          }
          .context()

  private fun region(label: String, size: Int = 4, offset: Int = 0) =
      FunctionDataWindow(FunctionDataAddress(label, offset), size)

  private fun contract(capacity: Int = 10) =
      FunctionCallerAccessCheck(
          listOf(region("left"), region("right")),
          listOf(region("left"), region("right")),
          listOf(region("right")),
          capacity,
      )

  private fun call(check: FunctionCallerAccessCheck = contract()) =
      FunctionCallCheck(
          "function",
          "returned",
          emptyMap(),
          mapOf("v0" to 0L),
          callerAccess = check,
      )

  private fun image(
      body: String,
      reverse: Boolean = false,
      wide: Boolean = false,
      gap: Int = 12,
      distant: Boolean = false,
  ) =
      session().use { machine ->
        val names = if (reverse) listOf("right", "left") else listOf("left", "right")
        val source =
            ".data ${if (wide) "0x100000000" else "0x10010000"}\n" +
                "${names[0]}: .word 11\ngap: .space $gap\n" +
                (if (distant) ".data 0x100000000\n" else "") +
                "${names[1]}: .word 22\noutside: .word 7\n" +
                $$".text\nmain: li $v0,10\nsyscall\nreturned: bc main\nfunction:\n$$body\nli $v0,0\njrc $ra\n"
        val path = directory.resolve("caller-access.asm")
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            extended = true,
            warnings = false,
        )
        machine.captureAssemblyImage {}
      }

  private fun evaluate(body: String, check: FunctionCallerAccessCheck = contract()) =
      PreparedFunctionChecks.evaluate(
              image(body),
              listOf(FunctionCheckCase("caller objects", listOf(call(check)))),
              ::session,
          )
          .single()

  @Test
  fun `private gaps and outside memory remain available under caller reordering and wide addresses`() {
    val body =
        $$"dla $t0,left\nlw $t1,0($t0)\ndla $t0,right\nsw $t1,0($t0)\n" +
            $$"dla $t0,gap\nsw $t1,0($t0)\nlw $t1,0($t0)\ndla $t0,outside\nsw $t1,0($t0)"
    for (reverse in listOf(false, true)) for (wide in listOf(false, true)) {
      val result =
          PreparedFunctionChecks.evaluate(
                  image(body, reverse, wide),
                  listOf(FunctionCheckCase("relocated objects", listOf(call()))),
                  ::session,
              )
              .single()
      assertTrue(result.passed, result.failure)
    }
  }

  @Test
  fun `same value forbidden stores and restore after writing cannot hide an access violation`() {
    for (body in
        listOf(
            $$"dla $t0,left\nlw $t1,0($t0)\nsw $t1,0($t0)",
            $$"dla $t0,left\nlw $t1,0($t0)\nsw $zero,0($t0)\nsw $t1,0($t0)",
            $$"dla $t0,left\nsd $zero,0($t0)",
            $$"dla $t0,left\nlw $t1,0($t0)",
        )) {
      val check =
          if (body.endsWith($$"lw $t1,0($t0)")) contract().copy(readable = emptyList())
          else contract()
      val result = evaluate(body, check)
      assertFalse(result.passed)
      assertTrue(result.failure!!.contains("permitted caller-object windows"), result.failure)
    }
    val sameValue = evaluate($$"dla $t0,left\nlw $t1,0($t0)\nsw $t1,0($t0)")
    assertFalse(sameValue.passed)
    assertTrue(sameValue.failure!!.contains("store"), sameValue.failure)
    val crossing =
        evaluate(
            $$"dla $t0,right\nlw $t1,0($t0)",
            contract()
                .copy(
                    objects = listOf(region("right", 2)),
                    readable = listOf(region("right", 2)),
                ),
        )
    assertFalse(crossing.passed)
    assertTrue(crossing.failure!!.contains("permitted caller-object windows"), crossing.failure)
  }

  @Test
  fun `one capacity includes gaps resets each call and excludes host setup`() {
    val body = $$"dla $t0,gap\nlw $t1,0($t0)\nlw $t1,4($t0)"
    val pass = evaluate(body, contract(2))
    assertTrue(pass.passed, pass.failure)
    val fail = evaluate(body, contract(1))
    assertFalse(fail.passed)
    assertTrue(fail.failure!!.contains("1 notices"), fail.failure)
    val setup =
        call(contract(2)).copy(setupMemory = listOf(FunctionMemoryCheck("left", "12345678")))
    val repeated =
        PreparedFunctionChecks.evaluate(
                image(body),
                listOf(FunctionCheckCase("reset history", listOf(setup, setup))),
                ::session,
            )
            .single()
    assertTrue(repeated.passed, repeated.failure)
  }

  @Test
  fun `valid permissions include exact aliases and overlapping caller objects`() {
    val check =
        contract()
            .copy(
                objects = listOf(region("left"), region("left", 2)),
                writable = listOf(region("left")),
            )
    val pass = evaluate($$"dla $t0,left\nlw $t1,0($t0)\nsw $t1,0($t0)", check)
    assertTrue(pass.passed, pass.failure)
  }

  @Suppress("UnusedDataClassCopyResult")
  @Test
  fun `objects and combined notice capacity are bounded before opening a session`() {
    for (bad in listOf(0, -1, 2_000_001)) assertThrows<IllegalArgumentException> { contract(bad) }
    for (objects in
        listOf(
            emptyList(),
            List(65) { region("left") },
            listOf(region("left", 0)),
            List(2) { region("left", 1_048_576) },
        )) {
      assertThrows<IllegalArgumentException> { contract().copy(objects = objects) }
    }
    assertThrows<IllegalArgumentException> {
      contract().copy(readable = List(65) { region("left") })
    }
    assertThrows<IllegalArgumentException> {
      contract().copy(writable = List(2) { region("left", 1_048_576) })
    }
    val policy =
        FunctionAccessPolicyCheck(
            FunctionDataAddress("left"),
            FunctionDataAddress("right"),
            maxAccesses = 1_000_000,
        )
    call(contract(2_000_000)).copy(accessPolicies = List(2) { policy })
    assertThrows<IllegalArgumentException> {
      call(contract(2_000_000)).copy(accessPolicies = List(3) { policy })
    }
    val objects = contract().objects.toMutableList()
    val case =
        FunctionCheckCase("mutated contract", listOf(call(contract().copy(objects = objects))))
    objects.clear()
    assertThrows<IllegalArgumentException> {
      PreparedFunctionChecks.evaluate(
          image("nop"),
          listOf(case),
          { error("Validate before session creation") },
      )
    }
  }

  @Test
  fun `nested caller lists are detached before machine creation`() {
    val objects = contract().objects.toMutableList()
    val reads = contract().readable.toMutableList()
    val writes = contract().writable.toMutableList()
    val case =
        FunctionCheckCase(
            "detached windows",
            listOf(call(contract().copy(objects = objects, readable = reads, writable = writes))),
        )
    val image = image($$"dla $t0,left\nlw $t1,0($t0)\ndla $t0,right\nsw $t1,0($t0)")
    val pass =
        PreparedFunctionChecks.evaluate(
                image,
                listOf(case),
                {
                  objects.clear()
                  reads.clear()
                  writes.clear()
                  session()
                },
            )
            .single()
    assertTrue(pass.passed, pass.failure)
  }

  @Test
  fun `large private gaps remain free while unrepresentable spans and unresolved objects fail`() {
    val largeGap =
        PreparedFunctionChecks.evaluate(
                image("nop", gap = 1_048_576),
                listOf(FunctionCheckCase("large private gap", listOf(call()))),
                ::session,
            )
            .single()
    assertTrue(largeGap.passed, largeGap.failure)
    val oversized =
        PreparedFunctionChecks.evaluate(
                image("nop", distant = true),
                listOf(FunctionCheckCase("oversized span", listOf(call()))),
                ::session,
            )
            .single()
    assertFalse(oversized.passed)
    assertTrue(oversized.failure!!.contains("Enclosing caller-object span"), oversized.failure)
    val missing = evaluate("nop", contract().copy(objects = listOf(region("absent"))))
    assertFalse(missing.passed)
  }
}
