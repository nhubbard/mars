package edu.missouristate.mars.desktop.venus;

import static edu.missouristate.mars.desktop.venus.FileStatus.*;

import java.io.File;
import java.util.function.IntConsumer;

/** Current editor/run state for one desktop window. */
public final class EditorState {
  private final IntConsumer menuStateChanged;

  public EditorState(IntConsumer menuStateChanged) {
    this.menuStateChanged = menuStateChanged;
  }

  private int systemStatus; // set to one of the above
  private boolean systemAssembled;
  private boolean systemSaved;
  private boolean systemEdited;
  private String systemName;
  private File systemFile;

  /**
   * Set file status. Also updates menu state accordingly.
   *
   * @param newStatus New status: EDITED, RUNNABLE, etc, see list above.
   */
  public void set(int newStatus) {
    systemStatus = newStatus;
    menuStateChanged.accept(systemStatus);
  }

  /**
   * Get file status
   *
   * @return file status EDITED, RUNNABLE, etc, see list above
   */
  public int get() {
    return systemStatus;
  }

  /**
   * Changes the value of assenbked to the parameter given.
   *
   * @param b boolean variable that tells what to set assembled to.
   */
  public void setAssembled(boolean b) {
    systemAssembled = b;
  }

  /**
   * Changes the value of saved to the parameter given.
   *
   * @param b boolean variable that tells what to set saved to .
   */
  public void setSaved(boolean b) {
    systemSaved = b;
  }

  /**
   * Changes the value of edited to the parameter given.
   *
   * @param b boolean variable that tells what to set edited to.
   */
  public void setEdited(boolean b) {
    systemEdited = b;
  }

  /**
   * Changes the value of name to the parameter given.
   *
   * @param s string variable tells what to set the name of the file to .
   */
  public void setName(String s) {
    systemName = s;
  }

  /**
   * Sets the file to the ASM file passed.
   *
   * @param f file object variable that stores the ASM file.
   */
  public void setFile(File f) {
    systemFile = f;
  }

  /**
   * Returns the ASM file.
   *
   * @return The ASM file.
   */
  public File getFile() {
    return systemFile;
  }

  /**
   * Returns the name of the file.
   *
   * @return The name of the ASM file.
   */
  public String getName() {
    return systemName;
  }

  /**
   * Tells whether the file has been assembled.
   *
   * @return Boolean value that is true if the ASM file has been assembled.
   */
  public boolean isAssembled() {
    return systemAssembled;
  }

  /**
   * Tells whether the file has been saved.
   *
   * @return Boolean variable that is true if the ASM file has been saved
   */
  public boolean isSaved() {
    return systemSaved;
  }

  /**
   * Tells whether the file has been edited since it has been saved.
   *
   * @return Boolean value that returns true if the ASM file has been edited.
   */
  public boolean isEdited() {
    return systemEdited;
  }

  /** Resets all the values in FileStatus */
  public void reset() {
    systemStatus = NO_FILE;
    systemName = "";
    systemAssembled = false;
    systemSaved = false;
    systemEdited = false;
    systemFile = null;
  }

  public void adopt(FileStatus file) {
    systemStatus = file.getFileStatus();
    systemName = file.getPathname() == null ? "" : file.getPathname();
    systemAssembled = false;
    systemSaved =
        systemStatus == NOT_EDITED
            || systemStatus == RUNNABLE
            || systemStatus == RUNNING
            || systemStatus == TERMINATED;
    systemEdited = file.hasUnsavedEdits();
    systemFile = file.getPathname() == null ? null : new File(file.getPathname());
  }
}
