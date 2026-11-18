package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.devices.DataCacheSimulator
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Base
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Mode
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Options as GridOptions
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.READ
import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataCacheSimulatorTest {
    @Test
    fun `cache, bitmap, and reference tools observe wide data without aliasing narrow locations`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("tools64.mars"), $$"""
            .data 0x100000000
            cell: .word 0x00123456
            .text 0x100400000
            .globl main
            main: dla $t0,cell
            lw $t1,0($t0)
            sw $t1,4($t0)
            sb $zero,5($t0)
        """.trimIndent())
        MarsSessionService.createMarsComponent().context().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.settings.setStartAtMainEnabled(true)
            val program = machine.assemble(listOf(source))
            val cache = DataCacheSimulator().apply { setLogging(true) }
            val bitmap = MemoryGridDisplay(captureAtLaunch = true)
            val instructions = MemoryGridDisplay(captureAtLaunch = true, mode = Mode.REFERENCES)
            bitmap.configure(GridOptions(base = Base.WIDE_DATA))
            instructions.configure(GridOptions(base = Base.WIDE_TEXT))
            machine.settings.setSelfModifyingCodeEnabled(true)
            cache.observe(machine.memory).use {
                val low = machine.memory.dataBaseAddress
                machine.memory.setWord(low, 0)
                machine.memory.getStatement(machine.memory.textBaseAddress)
                AccessOrigin.duringExecution<Unit, Exception> {
                    machine.memory.fetchInstruction(low)
                    cache.record(object : MemoryAccessNotice(READ, low, 0) {
                        override val isInstructionFetch = true
                    })
                }
                assertEquals(0L, cache.snapshot().accesses)
                bitmap.observe(machine.memory).use {
                    instructions.observe(machine.memory).use {
                        assertTrue(program.simulate(100))
                    }
                }
            }

            val first = 0x1_0000_0000L
            val cacheState = cache.snapshot()
            assertEquals(listOf(first, first + 4, first + 5), cacheState.recent.map { it.address })
            assertEquals(2L, cacheState.hits)
            assertEquals(first + 4, bitmap.bitmapCell(1)?.address)
            assertEquals(0x00120056, bitmap.bitmapCell(1)?.rgb)
            assertEquals(0x1_0040_0000L, instructions.referenceCell(0)?.address)
            assertEquals(1L, instructions.referenceCell(0)?.count)
        }
    }

}
