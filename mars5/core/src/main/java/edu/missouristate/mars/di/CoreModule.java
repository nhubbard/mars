package edu.missouristate.mars.di;

import dagger.Module;
import dagger.Provides;
import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsAssembler;
import edu.missouristate.mars.api.MarsMemory;
import edu.missouristate.mars.api.MarsRegisterFile;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.api.MarsSimulator;
import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.core.MarsMemoryAdapter;
import edu.missouristate.mars.core.SessionAssembler;
import edu.missouristate.mars.core.SimulationSettings;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.MemoryConfiguration;
import edu.missouristate.mars.mips.hardware.MemoryConfigurations;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import edu.missouristate.mars.simulator.BackStepper;
import edu.missouristate.mars.simulator.DelayedBranchState;
import java.util.Objects;
import javax.inject.Singleton;

/**
 * Dagger module for mars-core. Provides the headless-safe simulation settings and binds the {@link
 * MarsSettings} interface to the concrete {@link SimulationSettings} implementation.
 *
 * <p>{@link edu.missouristate.mars.api.MarsGuiProxy} is intentionally absent — callers supply it
 * via {@link MarsComponent.Builder#guiProxy} so the same component graph works for the CLI, the
 * IntelliJ plugin, and tests without any conditional logic here.
 */
@Module
public final class CoreModule {
  private final Settings settings;
  private final int maximumBacksteps;

  /** Creates a settings owner for this component, loading the existing user preferences. */
  public CoreModule() {
    this(new Settings());
  }

  /** Reuses a settings owner, e.g. Globals.getSettings() during legacy migration. */
  public CoreModule(Settings settings) {
    this(settings, Globals.maximumBacksteps);
  }

  /** Configures a session's undo-history capacity independently of other components. */
  public CoreModule(Settings settings, int maximumBacksteps) {
    if (maximumBacksteps < 1)
      throw new IllegalArgumentException("Backstep capacity must be positive");
    this.settings = Objects.requireNonNull(settings);
    this.maximumBacksteps = maximumBacksteps;
  }

  @Provides
  @Singleton
  Settings provideSettings() {
    return settings;
  }

  @Provides
  @Singleton
  static SimulationSettings provideSimulationSettings(Settings settings) {
    return settings.getSimulationSettings();
  }

  @Provides
  @Singleton
  static MarsSettings provideMarsSettings(SimulationSettings settings) {
    return settings;
  }

  @Provides
  @Singleton
  static IntegerRegisterFile provideRegisterFile(MemoryConfiguration configuration) {
    return new IntegerRegisterFile(
        configuration.getGlobalPointer(),
        configuration.getStackPointer(),
        configuration.getTextBaseAddress());
  }

  @Provides
  @Singleton
  static MarsRegisterFile provideMarsRegisterFile(IntegerRegisterFile registers) {
    return registers;
  }

  @Provides
  @Singleton
  static Coprocessor0State provideCoprocessor0() {
    return new Coprocessor0State();
  }

  @Provides
  @Singleton
  static Coprocessor1State provideCoprocessor1() {
    return new Coprocessor1State();
  }

  @Provides
  @Singleton
  static DelayedBranchState provideDelayedBranch() {
    return new DelayedBranchState();
  }

  @Provides
  @Singleton
  static MemoryConfiguration provideMemoryConfiguration(SimulationSettings settings) {
    return MemoryConfigurations.createConfiguration(settings.getMemoryConfiguration());
  }

  @Provides
  @Singleton
  static InstructionSet provideInstructionSet() {
    var instructions = new InstructionSet();
    instructions.populate();
    return instructions;
  }

  @Provides
  @Singleton
  static MemoryState provideMemory(
      MemoryConfiguration configuration, SimulationSettings settings, InstructionSet instructions) {
    return new MemoryState(
        configuration,
        settings,
        (binary, address) -> new ProgramStatement(binary, address, instructions));
  }

  @Provides
  @Singleton
  static MarsMemory provideMarsMemory(MemoryState memory, BackStepper backStepper) {
    return new MarsMemoryAdapter(memory, backStepper);
  }

  @Provides
  @Singleton
  BackStepper provideBackStepper(
      MemoryState memory,
      IntegerRegisterFile registers,
      Coprocessor0State coprocessor0,
      Coprocessor1State coprocessor1,
      DelayedBranchState branch) {
    return new BackStepper(memory, registers, coprocessor0, coprocessor1, branch, maximumBacksteps);
  }

  @Provides
  @Singleton
  static MarsAssembler provideAssembler(SessionAssembler assembler) {
    return assembler;
  }

  @Provides
  @Singleton
  static MarsSimulator provideSimulator(MarsContext session) {
    return session.simulator();
  }

  @Provides
  @Singleton
  static SymbolTable provideSymbolTable() {
    return new SymbolTable("global", null);
  }
}
