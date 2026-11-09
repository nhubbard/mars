package edu.missouristate.mars.api;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ordered entry files and captured text for one assembly. Texts include any separately selected
 * exception handler and files reached by {@code .include}. Each text is already decoded, with any
 * encoding marker removed. Paths are absolute and normalized lexically, so captured files need not
 * exist on disk.
 */
public record AssemblySourceSnapshot(List<Path> entries, Map<Path, String> texts) {

  public AssemblySourceSnapshot {
    entries = entries.stream().map(AssemblySourceSnapshot::normalize).toList();
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("At least one source file is required");
    }
    var normalized = new HashMap<Path, String>();
    texts.forEach(
        (path, text) -> {
          Path file = normalize(path);
          if (normalized.putIfAbsent(file, Objects.requireNonNull(text)) != null) {
            throw new IllegalArgumentException("Multiple source texts for " + file);
          }
        });
    texts = Map.copyOf(normalized);
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }
}
