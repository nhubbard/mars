package edu.missouristate.mars;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.mips.hardware.MemoryConfiguration;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

/**
 * Fresh, deterministic composition for characterization tests; no shared machine or preferences.
 */
final class TestSessions {
  private TestSessions() {}

  static MarsContext create() {
    var settings = new Settings(new MemoryPreferences());
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
        .guiProxy(NO_GUI)
        .build()
        .context();
  }

  static InstructionContext execution(MarsContext session) {
    return new InstructionContext(session, session.syscallEnvironment);
  }

  static void select(MarsContext session, MemoryConfiguration configuration) {
    session.setMemoryConfiguration(configuration.configurationIdentifier());
  }

  static final MarsGuiProxy NO_GUI =
      new MarsGuiProxy() {
        public boolean isGuiMode() {
          return false;
        }

        public void postMessage(String text) {}

        public void postRunMessage(String text) {}

        public String readInput(int length) {
          return "";
        }

        public String readInputWithPrompt(String prompt) {
          return "";
        }

        public void clearRunConsole() {}
      };

  private static final class MemoryPreferences extends AbstractPreferences {
    private final Map<String, String> values = new HashMap<>();

    MemoryPreferences() {
      super(null, "");
    }

    protected void putSpi(String key, String value) {
      values.put(key, value);
    }

    protected String getSpi(String key) {
      return values.get(key);
    }

    protected void removeSpi(String key) {
      values.remove(key);
    }

    protected void removeNodeSpi() {
      values.clear();
    }

    protected String[] keysSpi() {
      return values.keySet().toArray(String[]::new);
    }

    protected String[] childrenNamesSpi() {
      return new String[0];
    }

    protected AbstractPreferences childSpi(String name) {
      throw new UnsupportedOperationException();
    }

    protected void syncSpi() {}

    protected void flushSpi() {}
  }
}
