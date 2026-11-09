package edu.missouristate.mars.api;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ordered entry files and captured text for one assembly. Texts include any separately selected
 * exception handler and files reached by {@code .include}. Each text is already decoded, with any
 * encoding marker removed. Entry and text keys are absolute and normalized. When supplied,
 * {@code resolvedIncludes} maps each absolute include request to the file selected by the capture
 * provider. Request keys retain dot segments because a symbolic link can affect their meaning.
 * An absent request in that map is unresolved. A null map uses lexical resolution for virtual
 * snapshots that have no filesystem.
 */
public record AssemblySourceSnapshot(
    List<Path> entries, Map<Path, String> texts, Map<Path, Path> resolvedIncludes) {

  public AssemblySourceSnapshot(List<Path> entries, Map<Path, String> texts) {
    this(entries, texts, null);
  }

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
    if (resolvedIncludes != null) {
      var absoluteIncludes = new HashMap<Path, Path>();
      resolvedIncludes.forEach(
          (request, target) -> absoluteIncludes.put(request.toAbsolutePath(), normalize(target)));
      resolvedIncludes = Map.copyOf(absoluteIncludes);
    }
  }

  public Path resolveInclude(Path request) {
    return resolvedIncludes == null
        ? normalize(request)
        : resolvedIncludes.get(request.toAbsolutePath());
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }
}
