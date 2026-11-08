package edu.missouristate.mars.legacy;

import edu.missouristate.mars.*;
import edu.missouristate.mars.api.*;
import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.core.*;
import edu.missouristate.mars.mips.hardware.Memory;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Dagger-owned dependencies for the existing frontend; state is shared with compatibility clients.
 */
@Singleton
public final class LegacyServices {
  private final MarsContext context;
  private final Settings settings;
  private final LegacyStateAccess state;
  public final Memory memory;
  public final InstructionSet instructionSet;
  public final SymbolTable symbolTable;
  public final Object memoryAndRegistersLock;

  @Inject
  public LegacyServices(
      MarsContext context,
      Settings settings,
      Memory memory,
      LegacyStateAccess state,
      Object memoryAndRegistersLock) {
    this.context = context;
    this.settings = settings;
    this.memory = memory;
    this.state = state;
    this.memoryAndRegistersLock = memoryAndRegistersLock;
    instructionSet = context.instructionSet;
    symbolTable = context.symbolTable;
  }

  public MarsContext context() {
    return context;
  }

  public Settings getSettings() {
    return settings;
  }

  public InstructionContext instructionContext() {
    return new InstructionContext(context, state.environment());
  }

  public MIPSprogram getProgram() {
    return state.program();
  }

  public void setProgram(MIPSprogram program) {
    state.program(program);
  }

  public boolean debug() {
    return state.debug();
  }

  public void setDebug(boolean enabled) {
    state.debug(enabled);
  }

  public int exitCode() {
    return state.exitCode();
  }

  public void setExitCode(int code) {
    state.exitCode(code);
  }

  public boolean runSpeedPanelExists() {
    return state.runSpeedPanelExists();
  }

  public void setRunSpeedPanelExists(boolean exists) {
    state.runSpeedPanelExists(exists);
  }

  public MarsGuiProxy getGui() {
    return state.gui();
  }

  public void setGui(MarsGuiProxy gui) {
    state.gui(gui);
  }

  public SyscallEnvironment syscallEnvironment() {
    return state.environment();
  }

  public void setSyscallEnvironment(SyscallEnvironment environment) {
    state.environment(environment);
  }

  public void resetRegisters() {
    context.registerFile.resetRegisters();
    initializeProgramCounter(getSettings().getBooleanSetting(Settings.START_AT_MAIN));
  }

  public void initializeProgramCounter(boolean startAtMain) {
    int address = symbolTable.getAddress(SymbolTable.getStartLabel());
    context.registerFile.initializeProgramCounter(
        startAtMain
                && address != SymbolTable.NOT_FOUND
                && (context.memory.inTextSegment(address)
                    || context.memory.inKernelTextSegment(address))
            ? address
            : context.registerFile.getInitialProgramCounter());
  }
}
