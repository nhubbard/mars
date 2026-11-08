package edu.missouristate.mars.legacy;

import edu.missouristate.mars.MIPSprogram;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.SyscallEnvironment;

/** Live frontend state supplied at the compatibility composition boundary. */
public interface LegacyStateAccess {
  MIPSprogram program();

  void program(MIPSprogram program);

  boolean debug();

  void debug(boolean enabled);

  int exitCode();

  void exitCode(int code);

  boolean runSpeedPanelExists();

  void runSpeedPanelExists(boolean exists);

  MarsGuiProxy gui();

  void gui(MarsGuiProxy gui);

  SyscallEnvironment environment();

  void environment(SyscallEnvironment environment);
}
