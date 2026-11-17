package edu.missouristate.mars

import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.ExtendedInstruction
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class WarmSessionTest {
    @Test
    fun `shared definitions resist mutation through catalog and token APIs`() {
        val catalog = InstructionDefaults.forTarget(MarsTarget.MARS64)
        val editor = InstructionDefaults.editorCatalog()
        assertThrows(IllegalStateException::class.java) { catalog.populate() }
        assertThrows(IllegalStateException::class.java) { editor.populate() }
        assertThrows(UnsupportedOperationException::class.java) {
            (catalog.instructionList as MutableList<Instruction>).clear()
        }

        val form = catalog.matchOperator("daddiu")!!.first()
        catalog.matchOperator("daddiu")!!.clear()
        assertSame(form, catalog.matchOperator("daddiu")!!.first())
        assertSame(form, editor.matchOperator("daddiu")!!.first())
        assertNotNull(editor.matchOperator("mfhi"))
        assertNull(catalog.matchOperator("mfhi"))
        assertSame(catalog.findByBinaryCode(0x212a0001), editor.findByBinaryCode(0x212a0001))

        val templates = catalog.instructionList.filterIsInstance<ExtendedInstruction>()
            .first { it.hasCompactTranslation() }
        for (translation in listOf(templates.basicIntructionTemplateList, templates.compactBasicIntructionTemplateList)) {
            assertThrows(UnsupportedOperationException::class.java) { (translation as MutableList<String>).clear() }
        }

        val example = form.tokenList
        val operand = example[1]
        assertThrows(IllegalStateException::class.java) { form.createExampleTokenList(catalog) }
        assertThrows(IllegalStateException::class.java) { example.add(operand) }
        assertThrows(IllegalStateException::class.java) { example.remove(1) }
        assertThrows(IllegalStateException::class.java) { example[1] = example[0] }
        assertThrows(IllegalStateException::class.java) { example.processedLine = "changed" }
        assertThrows(IllegalStateException::class.java) { example.markEquivalenceExpansionIncomplete() }
        assertThrows(IllegalStateException::class.java) { operand.type = TokenTypes.IDENTIFIER }
        assertThrows(IllegalStateException::class.java) { operand.setOriginal(null, 9) }
        assertThrows(IllegalStateException::class.java) { operand.setOriginalStartPos(9) }
        assertThrows(IllegalStateException::class.java) { operand.setDiagnosticOrigin(example[0]) }
        assertThrows(IllegalStateException::class.java) { operand.setExpansionSource(example[0]) }
        assertThrows(IllegalStateException::class.java) { operand.setArgumentOrigin(example[0]) }
        assertThrows(IllegalStateException::class.java) { operand.setEquivalenceSource(null) }

        val clone = example.clone() as TokenList
        clone[1].type = TokenTypes.IDENTIFIER
        clone.remove(1)
        assertTrue(operand.type != TokenTypes.IDENTIFIER)
        assertSame(operand, example[1])
    }

    @Test
    fun `loading the same cached image supports independent simultaneous execution and target changes`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("shared.s"), $$"""
            .data
            cell: .word 0
            .text
            daddiu $t0,$t0,1
            nop
        """.trimIndent())
        val image = TestSessions.create().use { assembler ->
            assembler.selectTarget(MarsTarget.MARS64)
            assembler.assemble(listOf(source))
            assembler.captureAssemblyImage {}
        }
        TestSessions.create().use { first ->
            TestSessions.create().use { second ->
                first.loadAssemblyImage(image) {}
                second.loadAssemblyImage(image) {}
                val wide = InstructionDefaults.forTarget(MarsTarget.MARS64)
                assertSame(wide, first.instructionSet)
                assertSame(wide, second.instructionSet)

                val rendezvous = CyclicBarrier(2)
                val firstSteps = mutableListOf<StepEvent>()
                val secondSteps = mutableListOf<StepEvent>()
                val cell = first.memory.dataBaseAddress
                first.memory.setWord(cell, 17)
                first.memory.reservation = MemoryState.Reservation(cell.toLong(), 4)
                first.delayedBranch.register(first.memory.textBaseAddress)
                val secondBranch = second.delayedBranch.snapshot()
                val firstRun = first.prepareRun(10, firstSteps).runUntilAddressAsync(1) {
                    rendezvous.await(10, TimeUnit.SECONDS)
                    false
                }
                val secondRun = second.prepareRun(20, secondSteps).runUntilAddressAsync(1) {
                    rendezvous.await(10, TimeUnit.SECONDS)
                    false
                }
                assertEquals(StopEvent.Reason.STEP_LIMIT, firstRun.get(15, TimeUnit.SECONDS).reason)
                assertEquals(StopEvent.Reason.STEP_LIMIT, secondRun.get(15, TimeUnit.SECONDS).reason)
                assertEquals(1, firstSteps.size)
                assertEquals(1, secondSteps.size)
                assertEquals(11, first.registerFile.getValue(8))
                assertEquals(21, second.registerFile.getValue(8))
                assertEquals(0, second.memory.getWord(cell))
                assertNotNull(first.memory.reservation)
                assertNull(second.memory.reservation)
                assertEquals(secondBranch, second.delayedBranch.snapshot())
                assertTrue(first.delayedBranch.snapshot() != secondBranch)
                first.backStepper.backStep()
                assertEquals(10, first.registerFile.getValue(8))
                assertEquals(21, second.registerFile.getValue(8))

                first.selectTarget(MarsTarget.MARS32)
                assertNull(first.program)
                assertSame(InstructionDefaults.forTarget(MarsTarget.MARS32), first.instructionSet)
                assertSame(wide, second.instructionSet)
                assertNotNull(second.program)
                assertEquals(21, second.registerFile.getValue(8))

                // The narrow self-modifying text decoder must also switch away from Release 6.
                first.settings.setSelfModifyingCodeEnabled(true)
                first.memory.setWord(first.memory.textBaseAddress, 0x212a0001)
                assertEquals("addi", first.memory.inspectInstruction(first.memory.textBaseAddress)?.instruction?.name)
                assertEquals("beqc", second.instructionSet.findByBinaryCode(0x212a0001)?.name)

                first.loadAssemblyImage(image) {}
                assertSame(wide, first.instructionSet)
                assertEquals(0, first.registerFile.getValue(8))
                assertEquals(0, first.memory.getWord(cell))
                assertFalse(first.backStepper.canBackstep())
            }
        }
    }

    private fun MarsContext.prepareRun(register: Int, steps: MutableList<StepEvent>) = simulator().also { simulator ->
        registerFile.updateRegister(8, register)
        simulator.addListener(object : SimulatorListener {
            override fun onStep(event: StepEvent) {
                steps.add(event)
            }

            override fun onStop(event: StopEvent) = Unit

            override fun onError(event: ErrorEvent) = Unit
        })
    }
}
