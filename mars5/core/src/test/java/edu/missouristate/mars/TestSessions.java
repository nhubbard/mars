package edu.missouristate.mars;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.*;

final class TestSessions {
  private TestSessions() {}

  static MarsContext create() {
    var settings = new Settings();
    settings
        .getSimulationSettings()
        .setMemoryConfiguration("Default")
        .setDelayedBranchingEnabled(false)
        .setBareMachineEnabled(false)
        .setStartAtMainEnabled(false)
        .setExtendedAssemblerEnabled(true)
        .setWarningsAreErrors(false);
    return DaggerMarsComponent.builder()
        .coreModule(new CoreModule(settings))
        .guiProxy(
            new MarsGuiProxy() {
              public boolean isGuiMode() {
                return false;
              }

              public void postMessage(String message) {}

              public void postRunMessage(String message) {}

              public String readInput(int maxLength) {
                return "";
              }

              public String readInputWithPrompt(String prompt) {
                return "";
              }

              public void clearRunConsole() {}
            })
        .build()
        .context();
  }
}
