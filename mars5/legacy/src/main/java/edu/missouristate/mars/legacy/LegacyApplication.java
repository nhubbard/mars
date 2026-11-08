package edu.missouristate.mars.legacy;

import edu.missouristate.mars.legacy.di.*;

/** Composition entry point for the single existing Venus application and its standalone tools. */
public final class LegacyApplication {
  private LegacyApplication() {}

  private static class Holder {
    static final LegacyComponent COMPONENT = DaggerLegacyComponent.create();
  }

  public static LegacyComponent component() {
    return Holder.COMPONENT;
  }

  public static LegacyServices services() {
    return component().services();
  }
}
