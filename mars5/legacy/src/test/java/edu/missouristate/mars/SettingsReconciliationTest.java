package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.core.SimulationSettings;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.di.MarsComponent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.AbstractPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettingsReconciliationTest {
  @ParameterizedTest
  @ValueSource(
      ints = {
        Settings.DELAYED_BRANCHING_ENABLED,
        Settings.SELF_MODIFYING_CODE_ENABLED,
        Settings.WARNINGS_ARE_ERRORS,
        Settings.EXTENDED_ASSEMBLER_ENABLED,
        Settings.START_AT_MAIN,
        Settings.BARE_MACHINE_ENABLED
      })
  void booleanViewsSharePersistentAndTemporaryChanges(int id) {
    var preferences = new MemoryPreferences();
    var settings = new Settings(preferences);
    var simulation = settings.getSimulationSettings();
    var notifications = new AtomicInteger();
    settings.subscribeChange(ignored -> notifications.incrementAndGet());

    settings.setBooleanSettingNonPersistent(id, false);
    settings.setBooleanSetting(id, true);
    assertThat(readBoolean(simulation, id)).isTrue();
    assertThat(notifications.get()).isEqualTo(1);
    assertThat(readBoolean(new Settings(preferences).getSimulationSettings(), id)).isTrue();

    writeBoolean(simulation, id, false);
    assertThat(settings.getBooleanSetting(id)).isFalse();
    assertThat(notifications.get()).isEqualTo(1);
    // Session-only writes must not overwrite the saved GUI preference.
    assertThat(readBoolean(new Settings(preferences).getSimulationSettings(), id)).isTrue();

    settings.reset();
    assertThat(settings.getSimulationSettings()).isSameAs(simulation);
    assertThat(readBoolean(simulation, id)).isTrue();

    settings.setBooleanSettingNonPersistent(id, false);
    assertThat(readBoolean(simulation, id)).isFalse();
  }

  @Test
  void memoryConfigurationAndErrorLimitShareTheOwnerAndResetCorrectly() {
    var preferences = new MemoryPreferences();
    var settings = new Settings(preferences);
    var simulation = settings.getSimulationSettings();
    settings.setMemoryConfiguration("CompactDataAtZero");
    assertThat(simulation.getMemoryConfiguration()).isEqualTo("CompactDataAtZero");

    simulation.setMemoryConfiguration("CompactTextAtZero").setAssemblerErrorLimit(17);
    assertThat(settings.getMemoryConfiguration()).isEqualTo("CompactTextAtZero");
    assertThat(settings.getAssemblerErrorLimit()).isEqualTo(17);
    assertThat(new Settings(preferences).getMemoryConfiguration()).isEqualTo("CompactDataAtZero");
    assertThat(new Settings(preferences).getAssemblerErrorLimit())
        .isEqualTo(Globals.maximumErrorMessages);

    settings.setAssemblerErrorLimit(23);
    assertThat(simulation.getAssemblerErrorLimit()).isEqualTo(23);
    settings.reset();
    assertThat(simulation.getMemoryConfiguration()).isEqualTo("CompactDataAtZero");
    assertThat(simulation.getAssemblerErrorLimit()).isEqualTo(Globals.maximumErrorMessages);
  }

  @Test
  void daggerSharesOneSettingsOwnerPerGraph() {
    var first = component(new Settings(new MemoryPreferences()));
    var second = component(new Settings(new MemoryPreferences()));
    assertThat(first.context().settings).isSameAs(first.settings().getSimulationSettings());
    assertThat(first.simulationSettings()).isSameAs(first.context().settings);
    first.context().settings.setDelayedBranchingEnabled(true);
    second.context().settings.setDelayedBranchingEnabled(false);
    assertThat(first.settings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)).isTrue();
    assertThat(second.settings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)).isFalse();
  }

  @Test
  void daggerOwnsIndependentRegistersWithSessionSpecificResetAddresses() {
    var firstSettings = new Settings(new MemoryPreferences());
    firstSettings.getSimulationSettings().setMemoryConfiguration("CompactDataAtZero");
    var secondSettings = new Settings(new MemoryPreferences());
    secondSettings.getSimulationSettings().setMemoryConfiguration("Default");
    var first = component(firstSettings);
    var second = component(secondSettings);
    assertThat(first.registerFile()).isSameAs(first.context().registerFile);
    first.registerFile().setValue(8, 123);
    first.registerFile().setValue(33, 45);
    assertThat(second.registerFile().getValue(8)).isZero();
    assertThat(second.registerFile().getValue(33)).isZero();
    var configuration =
        edu.missouristate.mars.mips.hardware.LegacyMemoryConfigurations.createConfiguration(
            "CompactDataAtZero");
    assertThat(first.registerFile().getPC()).isEqualTo(configuration.getTextBaseAddress());
    assertThat(first.registerFile().getValue(28)).isEqualTo(configuration.getGlobalPointer());
    assertThat(first.registerFile().getValue(29)).isEqualTo(configuration.getStackPointer());
    assertThat(first.registerFile().getPC()).isNotEqualTo(second.registerFile().getPC());
    first.registerFile().reset();
    assertThat(first.registerFile().getValue(8)).isZero();
  }

  @Test
  void daggerOwnsIndependentCoprocessor0State() {
    var first = component(new Settings(new MemoryPreferences()));
    var second = component(new Settings(new MemoryPreferences()));
    assertThat(first.context().coprocessor0).isNotSameAs(second.context().coprocessor0);
    first.context().coprocessor0.updateRegister(12, 42);
    assertThat(second.context().coprocessor0.getValue(12))
        .isEqualTo(edu.missouristate.mars.mips.hardware.Coprocessor0State.DEFAULT_STATUS_VALUE);
    first.context().coprocessor0.resetRegisters();
    assertThat(first.context().coprocessor0.getValue(12))
        .isEqualTo(edu.missouristate.mars.mips.hardware.Coprocessor0State.DEFAULT_STATUS_VALUE);
  }

  @Test
  void daggerOwnsIndependentCoprocessor1RegistersAndFlags() {
    var first = component(new Settings(new MemoryPreferences()));
    var second = component(new Settings(new MemoryPreferences()));
    assertThat(first.context().coprocessor1).isNotSameAs(second.context().coprocessor1);
    first.context().coprocessor1.updateRegister(12, 42);
    first.context().coprocessor1.setConditionFlag(7);
    assertThat(second.context().coprocessor1.getValue(12)).isZero();
    assertThat(second.context().coprocessor1.getConditionFlag(7)).isZero();
    second.context().coprocessor1.updateRegister(12, 99);
    first.context().coprocessor1.resetRegisters();
    assertThat(first.context().coprocessor1.getValue(12)).isZero();
    assertThat(first.context().coprocessor1.getConditionFlags()).isZero();
    assertThat(second.context().coprocessor1.getValue(12)).isEqualTo(99);
  }

  @Test
  void legacyGlobalsCanShareTheirSettingsWithTheGraphAndErrorList() {
    var original = Globals.settings;
    try {
      Globals.settings = new Settings(new MemoryPreferences());
      var component = component(Globals.getSettings());
      component.context().settings.setAssemblerErrorLimit(3).setWarningsAreErrors(true);
      var errors = new ErrorList(Globals.getSettings().getSimulationSettings());
      assertThat(errors.getErrorLimit()).isEqualTo(3);
      for (int i = 0; i < 6; i++) {
        errors.add(new ErrorMessage((MIPSprogram) null, i + 1, 1, "Invalid instruction"));
      }
      assertThat(errors.errorLimitExceeded()).isTrue();
      assertThat(errors.getErrorMessages()).hasSize(4);
      assertThat(errors.getErrorMessages().get(3).getMessage())
          .isEqualTo("Error Limit of 3 exceeded.");
      assertThat(Globals.getSettings().getBooleanSetting(Settings.WARNINGS_ARE_ERRORS)).isTrue();
      Globals.getSettings().setBooleanSettingNonPersistent(Settings.WARNINGS_ARE_ERRORS, false);
      assertThat(component.context().settings.isWarningsAreErrors()).isFalse();
    } finally {
      Globals.settings = original;
    }
  }

  @Test
  void standaloneSettingsKeepIndependentInMemoryDefaults() {
    var first = new SimulationSettings();
    var second = new SimulationSettings();
    assertThat(second.getMemoryConfiguration()).isEqualTo("Default");
    assertThat(second.isExtendedAssemblerEnabled()).isTrue();
    assertThat(second.getAssemblerErrorLimit()).isEqualTo(200);
    first.setDelayedBranchingEnabled(true).setMemoryConfiguration("CompactDataAtZero");
    assertThat(second.isDelayedBranchingEnabled()).isFalse();
    assertThat(second.getMemoryConfiguration()).isEqualTo("Default");
  }

  private static boolean readBoolean(SimulationSettings settings, int id) {
    return switch (id) {
      case Settings.DELAYED_BRANCHING_ENABLED -> settings.isDelayedBranchingEnabled();
      case Settings.SELF_MODIFYING_CODE_ENABLED -> settings.isSelfModifyingCodeEnabled();
      case Settings.WARNINGS_ARE_ERRORS -> settings.isWarningsAreErrors();
      case Settings.EXTENDED_ASSEMBLER_ENABLED -> settings.isExtendedAssemblerEnabled();
      case Settings.START_AT_MAIN -> settings.isStartAtMainEnabled();
      case Settings.BARE_MACHINE_ENABLED -> settings.isBareMachineEnabled();
      default -> throw new IllegalArgumentException();
    };
  }

  private static void writeBoolean(SimulationSettings settings, int id, boolean value) {
    switch (id) {
      case Settings.DELAYED_BRANCHING_ENABLED -> settings.setDelayedBranchingEnabled(value);
      case Settings.SELF_MODIFYING_CODE_ENABLED -> settings.setSelfModifyingCodeEnabled(value);
      case Settings.WARNINGS_ARE_ERRORS -> settings.setWarningsAreErrors(value);
      case Settings.EXTENDED_ASSEMBLER_ENABLED -> settings.setExtendedAssemblerEnabled(value);
      case Settings.START_AT_MAIN -> settings.setStartAtMainEnabled(value);
      case Settings.BARE_MACHINE_ENABLED -> settings.setBareMachineEnabled(value);
      default -> throw new IllegalArgumentException();
    }
  }

  private static MarsComponent component(Settings settings) {
    return DaggerMarsComponent.builder()
        .coreModule(new CoreModule(settings))
        .guiProxy(NO_GUI)
        .build();
  }

  private static final MarsGuiProxy NO_GUI =
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
      };

  /** In-memory preferences keep these tests away from the user's saved settings. */
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
