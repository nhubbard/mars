package edu.missouristate.mars.plugin.venus;

import edu.missouristate.mars.ApplicationInfo;
import edu.missouristate.mars.util.FilenameFinder;
import java.io.File;
import javax.swing.filechooser.FileFilter;

/** Swing adapter for the core's extension filter. */
public final class FileChooserFilters {
  private FileChooserFilters() {}

  public static FileFilter assemblyFiles() {
    var delegate =
        FilenameFinder.getFileFilter(ApplicationInfo.fileExtensions, "Assembler Files", true);
    return new FileFilter() {
      @Override
      public boolean accept(File file) {
        return delegate.accept(file);
      }

      @Override
      public String getDescription() {
        return delegate.getDescription();
      }
    };
  }
}
