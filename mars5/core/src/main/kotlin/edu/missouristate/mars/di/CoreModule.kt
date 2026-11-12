package edu.missouristate.mars.di

import dagger.Module
import dagger.Provides
import edu.missouristate.mars.ApplicationInfo
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.Settings
import edu.missouristate.mars.api.MarsAssembler
import edu.missouristate.mars.api.MarsMemory
import edu.missouristate.mars.api.MarsRegisterFile
import edu.missouristate.mars.api.MarsSettings
import edu.missouristate.mars.api.MarsSimulator
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.core.MarsMemoryAdapter
import edu.missouristate.mars.core.SessionAssembler
import edu.missouristate.mars.core.SimulationSettings
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.Coprocessor1State
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile
import edu.missouristate.mars.mips.hardware.MemoryConfiguration
import edu.missouristate.mars.mips.hardware.MemoryConfigurations
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.simulator.BackStepper
import edu.missouristate.mars.simulator.DelayedBranchState
import javax.inject.Singleton

/** Supplies the hardware, settings, and services for one session. */
@Module
class CoreModule @JvmOverloads constructor(
    private val settings: Settings = Settings(),
    private val maximumBacksteps: Int = ApplicationInfo.maximumBacksteps,
) {
    init {
        require(maximumBacksteps >= 1) { "Backstep capacity must be positive" }
    }

    @Provides
    @Singleton
    fun provideSettings(): Settings = settings

    @Provides
    @Singleton
    fun provideBackStepper(
        memory: MemoryState, registers: IntegerRegisterFile, coprocessor0: Coprocessor0State,
        coprocessor1: Coprocessor1State, branch: DelayedBranchState,
    ): BackStepper = BackStepper(memory, registers, coprocessor0, coprocessor1, branch, maximumBacksteps)

    companion object {
        @Provides
        @Singleton
        fun provideSimulationSettings(settings: Settings): SimulationSettings = settings.simulationSettings

        @Provides
        @Singleton
        fun provideMarsSettings(settings: SimulationSettings): MarsSettings = settings

        @Provides
        @Singleton
        fun provideRegisterFile(configuration: MemoryConfiguration): IntegerRegisterFile =
            IntegerRegisterFile(configuration.globalPointer, configuration.stackPointer, configuration.textBaseAddress)

        @Provides
        @Singleton
        fun provideMarsRegisterFile(registers: IntegerRegisterFile): MarsRegisterFile = registers

        @Provides
        @Singleton
        fun provideCoprocessor0(): Coprocessor0State = Coprocessor0State()

        @Provides
        @Singleton
        fun provideCoprocessor1(): Coprocessor1State = Coprocessor1State()

        @Provides
        @Singleton
        fun provideDelayedBranch(): DelayedBranchState = DelayedBranchState()

        @Provides
        @Singleton
        fun provideMemoryConfiguration(settings: SimulationSettings): MemoryConfiguration =
            MemoryConfigurations.createConfiguration(settings.memoryConfiguration)

        @Provides
        @Singleton
        fun provideInstructionSet(): InstructionSet = InstructionSet().apply { populate() }

        @Provides
        @Singleton
        fun provideMemory(configuration: MemoryConfiguration, settings: SimulationSettings, instructions: InstructionSet): MemoryState =
            MemoryState(configuration, settings) { binary, address -> ProgramStatement(binary, address, instructions, settings) }

        @Provides
        @Singleton
        fun provideMarsMemory(memory: MemoryState, backStepper: BackStepper): MarsMemory = MarsMemoryAdapter(memory, backStepper)

        @Provides
        @Singleton
        fun provideAssembler(session: MarsContext): MarsAssembler = SessionAssembler(session)

        @Provides
        @Singleton
        fun provideSimulator(session: MarsContext): MarsSimulator = session.simulator()

        @Provides
        @Singleton
        fun provideSymbolTable(): SymbolTable = SymbolTable(null)
    }
}
