package edu.missouristate.mars.desktop.venus;

import edu.missouristate.mars.Settings;
import edu.missouristate.mars.desktop.venus.EditorFont;
import java.awt.Color;
import java.awt.Font;

/** Converts persisted editor values to UI objects without putting desktop types in the core. */
public final class EditorSettings {
  private final Settings settings;

  private EditorSettings(Settings settings) {
    this.settings = settings;
  }

  public static EditorSettings of(Settings settings) {
    return new EditorSettings(settings);
  }

  private static Font font(Settings.FontSpec value) {
    return value == null
        ? null
        : EditorFont.createFontFromStringValues(value.family(), value.style(), value.size());
  }

  private static Color color(Integer value) {
    return value == null ? null : new Color(value);
  }

  public Font getEditorFont() {
    return getFontByPosition(Settings.EDITOR_FONT);
  }

  public Font getFontByPosition(int position) {
    return font(settings.getFontSpec(position));
  }

  public Font getDefaultFontByPosition(int position) {
    return font(settings.getDefaultFontSpec(position));
  }

  public void setEditorFont(Font value) {
    setFontByPosition(Settings.EDITOR_FONT, value);
  }

  public void setFontByPosition(int position, Font value) {
    settings.setFontSpec(
        position,
        new Settings.FontSpec(
            value.getFamily(),
            EditorFont.styleIntToStyleString(value.getStyle()),
            EditorFont.sizeIntToSizeString(value.getSize())));
  }

  public Color getColorSettingByKey(String key) {
    return color(settings.getColorSettingByKey(key));
  }

  public Color getDefaultColorSettingByKey(String key) {
    return color(settings.getDefaultColorSettingByKey(key));
  }

  public Color getColorSettingByPosition(int position) {
    return color(settings.getColorSettingByPosition(position));
  }

  public Color getDefaultColorSettingByPosition(int position) {
    return color(settings.getDefaultColorSettingByPosition(position));
  }

  public void setColorSettingByKey(String key, Color value) {
    settings.setColorSettingByKey(key, value.getRGB());
  }

  public void setColorSettingByPosition(int position, Color value) {
    settings.setColorSettingByPosition(position, value.getRGB());
  }

  public void setEditorSyntaxStyleByPosition(int index, Color value, boolean italic, boolean bold) {
    settings.setEditorSyntaxStyleByPosition(index, value.getRGB(), italic, bold);
  }

  public Color getEditorSyntaxStyleColorByPosition(int index) {
    return color(settings.getEditorSyntaxStyleColorByPosition(index));
  }

  public Color getDefaultEditorSyntaxStyleColorByPosition(int index) {
    return color(settings.getDefaultEditorSyntaxStyleColorByPosition(index));
  }
}
