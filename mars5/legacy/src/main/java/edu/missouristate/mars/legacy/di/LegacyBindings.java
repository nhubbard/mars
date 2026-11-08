package edu.missouristate.mars.legacy.di;

import dagger.Module;
import dagger.Provides;
import edu.missouristate.mars.*;
import edu.missouristate.mars.api.*;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.legacy.LegacyStateAccess;
import edu.missouristate.mars.mips.hardware.Memory;
import javax.inject.Singleton;

/**
 * The deliberate compatibility boundary: deprecated process state is adapted once here, rather than
 * read directly by application code. No duplicate hardware or frontend state is created.
 */
@Module
public final class LegacyBindings {
  private LegacyBindings() {}

  @Provides
  @Singleton
  static MarsContext context() {
    return Globals.context();
  }

  @Provides
  @Singleton
  static Settings settings(MarsContext context) {
    return Globals.getSettings();
  }

  @Provides
  @Singleton
  static Memory memory(MarsContext context) {
    return Globals.memory;
  }

  @Provides
  @Singleton
  static Object hardwareLock() {
    return Globals.memoryAndRegistersLock;
  }

  @Provides
  @Singleton
  static LegacyStateAccess state() {
    return new LegacyStateAccess() {
      public MIPSprogram program() {
        return Globals.program;
      }

      public void program(MIPSprogram program) {
        Globals.program = program;
      }

      public boolean debug() {
        return Globals.debug;
      }

      public void debug(boolean enabled) {
        Globals.debug = enabled;
      }

      public int exitCode() {
        return Globals.exitCode;
      }

      public void exitCode(int code) {
        Globals.exitCode = code;
      }

      public boolean runSpeedPanelExists() {
        return Globals.runSpeedPanelExists;
      }

      public void runSpeedPanelExists(boolean exists) {
        Globals.runSpeedPanelExists = exists;
      }

      public MarsGuiProxy gui() {
        return Globals.getGui();
      }

      public void gui(MarsGuiProxy gui) {
        Globals.setGui(gui);
      }

      public SyscallEnvironment environment() {
        return Globals.syscallEnvironment();
      }

      public void environment(SyscallEnvironment environment) {
        Globals.setSyscallEnvironment(environment);
      }
    };
  }
}
