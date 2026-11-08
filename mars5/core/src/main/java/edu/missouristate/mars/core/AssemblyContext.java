package edu.missouristate.mars.core;

import edu.missouristate.mars.*;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.assembler.Tokenizer;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.hardware.Register;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import edu.missouristate.mars.util.SystemIO;
import java.util.Objects;

/** Assembly dependencies shared by a program's source files, includes, and pseudo-instructions. */
public final class AssemblyContext {
  private final MarsContext session;

  public AssemblyContext(MarsContext session) {
    this.session = Objects.requireNonNull(session);
  }

  private AssemblyContext() {
    session = null;
  }

  private static final AssemblyContext LEGACY = new AssemblyContext();

  public static AssemblyContext legacy() {
    return LEGACY;
  }

  public MarsContext session() {
    return session;
  }

  public boolean sharesSession(AssemblyContext other) {
    return session == other.session;
  }

  public MemoryState memory() {
    return session == null ? Globals.memory.legacyState() : session.memory;
  }

  public SymbolTable symbols() {
    return session == null ? Globals.symbolTable : session.symbolTable;
  }

  public InstructionSet instructions() {
    return session == null ? Globals.instructionSet : session.instructionSet;
  }

  public MarsSettings settings() {
    return session == null ? Globals.getSettings().getSimulationSettings() : session.settings;
  }

  public ErrorList newErrorList() {
    return session == null ? new ErrorList() : new ErrorList(session.settings);
  }

  public boolean bareMachineEnabled() {
    return session == null
        ? Globals.getSettings().getBareMachineEnabled()
        : session.settings.isBareMachineEnabled();
  }

  public boolean debug() {
    return session == null && Globals.debug;
  }

  public boolean displayAddressesInHex() {
    return session != null
        || Globals.getSettings().getBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX);
  }

  public Tokenizer tokenizer(MIPSprogram program) {
    return new Tokenizer(program, instructions());
  }

  public Register integerRegister(String name) {
    return session == null
        ? RegisterFile.getUserRegister(name)
        : session.registerFile.getUserRegister(name);
  }

  public int integerRegisterNumber(String name) {
    return session == null ? RegisterFile.getNumber(name) : session.registerFile.getNumber(name);
  }

  public int floatingRegisterNumber(String name) {
    return session == null
        ? Coprocessor1.getRegisterNumber(name)
        : session.coprocessor1.getRegisterNumber(name);
  }

  public void clearMemory() {
    if (session == null) Globals.memory.clear();
    else {
      session.backStepper.clear();
      session.memory.clear();
    }
  }

  public void resetLegacyFiles() {
    if (session == null) SystemIO.resetFiles();
  }

  public void beginAssembly(MIPSprogram program) {
    if (session != null) session.beginAssembly(program);
  }

  public void finishAssembly(MIPSprogram program) {
    if (session != null) session.finishAssembly(program);
  }
}
