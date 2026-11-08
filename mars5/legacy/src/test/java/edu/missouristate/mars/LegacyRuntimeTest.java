package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.*;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.mips.hardware.*;
import org.junit.jupiter.api.Test;

class LegacyRuntimeTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

  @Test
  void assemblyInitializesOnlyTheCanonicalLegacyFileTable() throws Exception {
    var source = directory.resolve("program.s");
    java.nio.file.Files.writeString(source, ".text\nnop\n");
    var program = new LegacyProgram();
    var sources =
        program.prepareFilesForAssembly(
            new java.util.ArrayList<>(java.util.List.of(source.toString())),
            source.toString(),
            null);
    program.assemble(sources, true, false);
    int descriptor =
        edu.missouristate.mars.util.SystemIO.openFile(
            directory.resolve("output.bin").toString(), 1);
    try {
      assertThat(descriptor).isEqualTo(3);
    } finally {
      edu.missouristate.mars.util.SystemIO.closeFile(descriptor);
    }
  }

  @Test
  void everyCompatibilityFacadeSharesTheCanonicalHardwareAndLiveFrontend() {
    var context = Globals.context();
    assertThat(Memory.getInstance().legacyState()).isSameAs(context.memory);
    assertThat(RegisterFile.legacyState()).isSameAs(context.registerFile);
    assertThat(Coprocessor0.legacyState()).isSameAs(context.coprocessor0);
    assertThat(Coprocessor1.legacyState()).isSameAs(context.coprocessor1);
    var previous = Globals.getGui();
    try {
      Globals.setGui(
          new MarsGuiProxy() {
            public boolean isGuiMode() {
              return true;
            }

            public void postMessage(String value) {}

            public void postRunMessage(String value) {}

            public String readInput(int length) {
              return "input";
            }

            public String readInputWithPrompt(String prompt) {
              return "prompted";
            }

            public void clearRunConsole() {}
          });
      assertThat(context.gui.isGuiMode()).isTrue();
      assertThat(context.gui.readInput(10)).isEqualTo("input");
      assertThat(context.gui.readInputWithPrompt("test")).isEqualTo("prompted");
    } finally {
      Globals.setGui(previous);
    }
    assertThat(context.gui.isGuiMode()).isEqualTo(previous.isGuiMode());
  }
}
