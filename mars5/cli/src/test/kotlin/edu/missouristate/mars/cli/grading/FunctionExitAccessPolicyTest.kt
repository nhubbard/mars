package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class FunctionExitAccessPolicyTest {
  @TempDir lateinit var directory: Path

  private fun session() =
      DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()

  private fun image() =
      session().use { machine ->
        val path = directory.resolve("continuation.asm")
        val source =
            $$"""
            .data
            output: .word 0
            end: .word 0
            .text
            main: li $v0,10
            syscall
            function: li $v0,7
            jrc $ra
            returned:
            la $t0,output
            sw $zero,0($t0)
            li $v0,10
            syscall
            """
                .trimIndent()
        machine.assembleSnapshot(
            AssemblySourceSnapshot(listOf(path), mapOf(path to source)),
            null,
            true,
            false,
        )
        machine.captureAssemblyImage {}
      }

  private fun contract(policies: List<FunctionAccessPolicyCheck>) =
      FunctionCheckCase(
          "continuation footprint",
          listOf(FunctionCallCheck("function", "returned", emptyMap(), mapOf("v0" to 7L))),
          exit =
              FunctionExitCheck(
                  memory = listOf(FunctionMemoryCheck("output", "00000000")),
                  accessPolicies = policies,
              ),
      )

  private fun policy(writable: List<FunctionDataWindow>) =
      FunctionAccessPolicyCheck(
          FunctionDataAddress("output"),
          FunctionDataAddress("end", 4),
          writable = writable,
          checkReads = false,
      )

  @Test
  fun `an explicit continuation policy catches a restored store while legacy caller publication remains permitted`() {
    val image = image()
    assertTrue(
        PreparedFunctionChecks.evaluate(image, listOf(contract(emptyList())), ::session)
            .single()
            .passed,
    )
    val forbidden =
        PreparedFunctionChecks.evaluate(
                image,
                listOf(contract(listOf(policy(emptyList())))),
                ::session,
            )
            .single()
    assertFalse(forbidden.passed)
    assertTrue(
        forbidden.failure!!.contains("outside its permitted caller windows"),
        forbidden.failure,
    )
    val allowed = listOf(FunctionDataWindow(FunctionDataAddress("output"), 4))
    assertTrue(
        PreparedFunctionChecks.evaluate(image, listOf(contract(listOf(policy(allowed)))), ::session)
            .single()
            .passed,
    )
  }

  @Test
  fun `exit policy windows are captured before session acquisition and failures close their observers`() {
    val writable = mutableListOf<FunctionDataWindow>()
    val case = contract(listOf(policy(writable)))
    val machines = mutableListOf<MarsContext>()
    val result =
        PreparedFunctionChecks.evaluate(
                image(),
                listOf(case),
                {
                  writable += FunctionDataWindow(FunctionDataAddress("output"), 4)
                  session().also { machines += it }
                },
            )
            .single()
    assertFalse(result.passed)
    assertEquals(1, machines.size)
    assertEquals(0, machines.single().memory.countObservers())
    assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
    // A fresh attempt has no retained policy history from the failed continuation.
    assertTrue(
        PreparedFunctionChecks.evaluate(image(), listOf(contract(emptyList())), ::session)
            .single()
            .passed,
    )
  }
}
