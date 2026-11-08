package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.assembler.Token;
import edu.missouristate.mars.assembler.TokenTypes;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.legacy.LegacyApplication;
import edu.missouristate.mars.mips.hardware.Memory;
import org.junit.jupiter.api.Test;

/** Deliberate contract checks between the deprecated external API and the frontend graph. */
class LegacyServicesTest {
  @Test
  void compositionSharesCompatibilityHardwareAndLiveFrontendState() {
    var services = LegacyApplication.services();
    assertThat(LegacyApplication.component().services()).isSameAs(services);
    assertThat(services.context()).isSameAs(Globals.context());
    assertThat(services.memory).isSameAs(Memory.getInstance()).isSameAs(Globals.memory);
    assertThat(services.memory.legacyState()).isSameAs(services.context().memory);
    assertThat(services.getSettings()).isSameAs(Globals.getSettings());
    assertThat(services.memoryAndRegistersLock).isSameAs(Globals.memoryAndRegistersLock);
    var previous = Globals.program;
    int exitCode = Globals.exitCode;
    try {
      var program = new LegacyProgram();
      services.setProgram(program);
      assertThat(Globals.program).isSameAs(program);
      Globals.program = null;
      assertThat(services.getProgram()).isNull();
      Globals.exitCode = 17;
      assertThat(services.exitCode()).isEqualTo(17);
      services.setExitCode(23);
      assertThat(Globals.exitCode).isEqualTo(23);
    } finally {
      Globals.program = previous;
      Globals.exitCode = exitCode;
    }
  }

  @Test
  void resetUsesMainLabelAndSessionWritesKeepUndoAndIndependentHardware() {
    var services = LegacyApplication.services();
    var context = services.context();
    var settings = services.getSettings();
    boolean startAtMain = settings.getBooleanSetting(Settings.START_AT_MAIN);
    int initial = context.registerFile.getProgramCounter();
    int t0 = context.registerFile.getValue(8);
    var oldMain = context.symbolTable.getSymbol("main");
    try {
      context.symbolTable.removeSymbol(new Token(TokenTypes.IDENTIFIER, "main", null, 1, 1));
      int main = context.memory.textBaseAddress + 32;
      context.symbolTable.addSymbol(
          new Token(TokenTypes.IDENTIFIER, "main", null, 1, 1),
          main,
          false,
          new ErrorList(context.settings));
      settings.setBooleanSettingNonPersistent(Settings.START_AT_MAIN, true);
      services.resetRegisters();
      assertThat(context.registerFile.getProgramCounter()).isEqualTo(main);
      context.backStepper.clear();
      services.instructionContext().updateRegister(8, 42);
      assertThat(context.registerFile.getValue(8)).isEqualTo(42);
      assertThat(context.backStepper.empty()).isFalse();
      context.backStepper.backStep();
      assertThat(context.registerFile.getValue(8)).isZero();
      try (var independent =
          DaggerMarsComponent.builder().guiProxy(context.gui).build().context()) {
        assertThat(independent.registerFile).isNotSameAs(context.registerFile);
        assertThat(independent.memory).isNotSameAs(context.memory);
      }
    } finally {
      context.symbolTable.removeSymbol(new Token(TokenTypes.IDENTIFIER, "main", null, 1, 1));
      if (oldMain != null)
        context.symbolTable.addSymbol(
            new Token(TokenTypes.IDENTIFIER, "main", null, 1, 1),
            oldMain.getAddress(),
            oldMain.getType(),
            new ErrorList(context.settings));
      settings.setBooleanSettingNonPersistent(Settings.START_AT_MAIN, startAtMain);
      context.registerFile.initializeProgramCounter(initial);
      context.registerFile.setValue(8, t0);
      context.backStepper.clear();
    }
  }
}
