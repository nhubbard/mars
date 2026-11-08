package edu.missouristate.mars;

import static org.junit.jupiter.api.Assertions.*;

import edu.missouristate.mars.plugin.venus.EditorSettings;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import org.junit.jupiter.api.Test;

class EditorSettingsTest {
  @Test
  void editorValuesKeepTheirPersistedRepresentationAndRoundTripThroughTheUiAdapter() {
    var preferences = new MemoryPreferences();
    var settings = new Settings(preferences);
    var editor = EditorSettings.of(settings);
    var font = new Font("Monospaced", Font.BOLD | Font.ITALIC, 18);
    editor.setEditorFont(font);
    editor.setColorSettingByPosition(Settings.EVEN_ROW_BACKGROUND, new Color(0x123456));
    var restored = new Settings(preferences);
    assertEquals(font, EditorSettings.of(restored).getEditorFont());
    assertEquals(
        new Color(0x123456),
        EditorSettings.of(restored).getColorSettingByPosition(Settings.EVEN_ROW_BACKGROUND));
    assertEquals(0x123456, restored.getColorSettingByPosition(Settings.EVEN_ROW_BACKGROUND));
    assertNull(editor.getFontByPosition(-1));
    assertNull(editor.getColorSettingByPosition(-1));
  }

  @Test
  void syntaxDefaultsBelongToEachSettingsOwner() {
    var first = new Settings(new MemoryPreferences());
    first.initializeSyntaxStyleDefaults(
        new String[] {"0x112233"}, new boolean[] {true}, new boolean[] {false});
    var second = new Settings(new MemoryPreferences());
    second.initializeSyntaxStyleDefaults(
        new String[] {"0x445566"}, new boolean[] {false}, new boolean[] {true});
    assertEquals(0x112233, first.getEditorSyntaxStyleColorByPosition(0));
    assertEquals(0x445566, second.getEditorSyntaxStyleColorByPosition(0));
    assertTrue(first.getEditorSyntaxStyleBoldByPosition(0));
    assertFalse(second.getEditorSyntaxStyleBoldByPosition(0));
  }

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
