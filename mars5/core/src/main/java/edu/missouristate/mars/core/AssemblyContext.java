package edu.missouristate.mars.core;

import edu.missouristate.mars.*;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.assembler.Tokenizer;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.hardware.Register;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import java.util.Objects;

/** Assembly dependencies shared by a program's source files, includes, and pseudo-instructions. */
public record AssemblyContext(MarsContext session) {
  public AssemblyContext(MarsContext session) {
    this.session = Objects.requireNonNull(session);
  }

  public boolean sharesSession(AssemblyContext other) {
    return session == other.session;
  }

  public MemoryState memory() {
    return session.memory;
  }

  public SymbolTable symbols() {
    return session.symbolTable;
  }

  public InstructionSet instructions() {
    return session.instructionSet;
  }

  public MarsSettings settings() {
    return session.settings;
  }

  public ErrorList newErrorList() {
    return new ErrorList(session.settings);
  }

  public boolean bareMachineEnabled() {
    return session.settings.isBareMachineEnabled();
  }

  public boolean debug() {
    return false;
  }

  public boolean displayAddressesInHex() {
    return session.settings.displayAddressesInHex();
  }

  public Tokenizer tokenizer(MIPSprogram program) {
    return new Tokenizer(program, instructions());
  }

  public Register integerRegister(String name) {
    return session.registerFile.getUserRegister(name);
  }

  public int integerRegisterNumber(String name) {
    return session.registerFile.getNumber(name);
  }

  public int floatingRegisterNumber(String name) {
    return session.coprocessor1.getRegisterNumber(name);
  }

  public void clearMemory() {
    session.backStepper.clear();
    session.memory.clear();
  }

  public void beginAssembly(MIPSprogram program) {
    session.beginAssembly(program);
  }

  public void finishAssembly(MIPSprogram program) {
    session.finishAssembly(program);
  }
}
