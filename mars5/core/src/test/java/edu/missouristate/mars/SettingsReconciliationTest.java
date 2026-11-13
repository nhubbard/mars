package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.core.SimulationSettings;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.di.MarsComponent;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.MemoryConfigurations;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.AbstractPreferences;
import org.jetbrains.annotations.NotNull;
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

    clearBoolean(simulation, id);
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
        .isEqualTo(ApplicationInfo.maximumErrorMessages);

    settings.setAssemblerErrorLimit(23);
    assertThat(simulation.getAssemblerErrorLimit()).isEqualTo(23);
    settings.reset();
    assertThat(simulation.getMemoryConfiguration()).isEqualTo("CompactDataAtZero");
    assertThat(simulation.getAssemblerErrorLimit()).isEqualTo(ApplicationInfo.maximumErrorMessages);
  }

  @Test
  void fontDefaultsAndSavedOverridesUseIndependentFields() {
    var preferences = new MemoryPreferences();
    var settings = new Settings(preferences);
    var defaults = new Settings.FontSpec("Monospaced", "Plain", "12");
    assertThat(settings.getFontSpec(Settings.EDITOR_FONT)).isEqualTo(defaults);
    assertThat(settings.getDefaultFontSpec(Settings.EDITOR_FONT)).isEqualTo(defaults);

    var saved = new Settings.FontSpec("Serif", "Bold", "18");
    settings.setFontSpec(Settings.EDITOR_FONT, saved);
    var reloaded = new Settings(preferences);
    assertThat(reloaded.getFontSpec(Settings.EDITOR_FONT)).isEqualTo(saved);
    assertThat(reloaded.getDefaultFontSpec(Settings.EDITOR_FONT)).isEqualTo(defaults);
  }

  @Test
  void daggerSharesOneSettingsOwnerPerGraph() {
    var first = component(new Settings(new MemoryPreferences()));
    var second = component(new Settings(new MemoryPreferences()));
    try (var firstContext = first.context(); var secondContext = second.context()) {
      assertThat(firstContext.settings).isSameAs(first.settings().getSimulationSettings());
      assertThat(first.simulationSettings()).isSameAs(firstContext.settings);
      firstContext.settings.setDelayedBranchingEnabled(true);
      secondContext.settings.setDelayedBranchingEnabled(false);
      assertThat(first.settings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)).isTrue();
      assertThat(second.settings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)).isFalse();
    }
  }

  @Test
  void daggerOwnsIndependentRegistersWithSessionSpecificResetAddresses() {
    var firstSettings = new Settings(new MemoryPreferences());
    firstSettings.getSimulationSettings().setMemoryConfiguration("CompactDataAtZero");
    var secondSettings = new Settings(new MemoryPreferences());
    secondSettings.getSimulationSettings().setMemoryConfiguration("Default");
    var first = component(firstSettings);
    var second = component(secondSettings);
    try (var firstContext = first.context(); var secondContext = second.context()) {
      assertThat(first.registerFile()).isSameAs(firstContext.registerFile);
      first.registerFile().setValue(8, 123);
      first.registerFile().setValue(33, 45);
      assertThat(secondContext.registerFile.getValue(8)).isZero();
      assertThat(secondContext.registerFile.getValue(33)).isZero();
      var configuration = MemoryConfigurations.createConfiguration("CompactDataAtZero");
      assertThat(first.registerFile().getPC()).isEqualTo(configuration.getTextBaseAddress());
      assertThat(first.registerFile().getValue(28)).isEqualTo(configuration.getGlobalPointer());
      assertThat(first.registerFile().getValue(29)).isEqualTo(configuration.getStackPointer());
      assertThat(first.registerFile().getPC()).isNotEqualTo(secondContext.registerFile.getPC());
      first.registerFile().reset();
      assertThat(first.registerFile().getValue(8)).isZero();
    }
  }

  @Test
  void daggerOwnsIndependentCoprocessor0State() {
    var first = component(new Settings(new MemoryPreferences()));
    var second = component(new Settings(new MemoryPreferences()));
    try (var firstContext = first.context(); var secondContext = second.context()) {
      assertThat(firstContext.coprocessor0).isNotSameAs(secondContext.coprocessor0);
      firstContext.coprocessor0.updateRegister(12, 42);
      assertThat(secondContext.coprocessor0.getValue(12))
          .isEqualTo(Coprocessor0State.DEFAULT_STATUS_VALUE);
      firstContext.coprocessor0.resetRegisters();
      assertThat(firstContext.coprocessor0.getValue(12))
          .isEqualTo(Coprocessor0State.DEFAULT_STATUS_VALUE);
    }
  }

  @Test
  void daggerOwnsIndependentCoprocessor1RegistersAndFlags() {
    var first = component(new Settings(new MemoryPreferences()));
    var second = component(new Settings(new MemoryPreferences()));
    try (var firstContext = first.context(); var secondContext = second.context()) {
      assertThat(firstContext.coprocessor1).isNotSameAs(secondContext.coprocessor1);
      firstContext.coprocessor1.updateRegister(12, 42);
      firstContext.coprocessor1.setConditionFlag(7);
      assertThat(secondContext.coprocessor1.getValue(12)).isZero();
      assertThat(secondContext.coprocessor1.getConditionFlag(7)).isZero();
      secondContext.coprocessor1.updateRegister(12, 99);
      firstContext.coprocessor1.resetRegisters();
      assertThat(firstContext.coprocessor1.getValue(12)).isZero();
      assertThat(firstContext.coprocessor1.getConditionFlags()).isZero();
      assertThat(secondContext.coprocessor1.getValue(12)).isEqualTo(99);
    }
  }

  @Test
  void settingsOwnerIsSharedWithTheGraphAndErrorList() {
    var settings = new Settings(new MemoryPreferences());

    var component = component(settings);
    try (var context = component.context()) {
      context.settings.setAssemblerErrorLimit(3).setWarningsAreErrors(true);
      var errors = new ErrorList(settings.getSimulationSettings());
      assertThat(errors.getErrorLimit()).isEqualTo(3);
      for (int i = 0; i < 6; i++) {
        errors.add(new ErrorMessage(null, i + 1, 1, "Invalid instruction"));
      }
      assertThat(errors.errorLimitExceeded()).isTrue();
      assertThat(errors.getErrorMessages()).hasSize(4);
      assertThat(errors.getErrorMessages().get(3).getMessage())
          .isEqualTo("Error Limit of 3 exceeded.");
      assertThat(settings.getBooleanSetting(Settings.WARNINGS_ARE_ERRORS)).isTrue();
      settings.setBooleanSettingNonPersistent(Settings.WARNINGS_ARE_ERRORS, false);
      assertThat(context.settings.isWarningsAreErrors()).isFalse();
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

  private static void clearBoolean(SimulationSettings settings, int id) {
    switch (id) {
      case Settings.DELAYED_BRANCHING_ENABLED -> settings.setDelayedBranchingEnabled(false);
      case Settings.SELF_MODIFYING_CODE_ENABLED -> settings.setSelfModifyingCodeEnabled(false);
      case Settings.WARNINGS_ARE_ERRORS -> settings.setWarningsAreErrors(false);
      case Settings.EXTENDED_ASSEMBLER_ENABLED -> settings.setExtendedAssemblerEnabled(false);
      case Settings.START_AT_MAIN -> settings.setStartAtMainEnabled(false);
      case Settings.BARE_MACHINE_ENABLED -> settings.setBareMachineEnabled(false);
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

        public void postMessage(@NotNull String message) {}

        public void postRunMessage(@NotNull String message) {}

        public @NotNull String readInput(int maxLength) {
          return "";
        }

        public @NotNull String readInputWithPrompt(@NotNull String prompt) {
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
