package edu.missouristate.mars;

import java.util.Observable;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Contains various IDE settings.  Persistent settings are maintained for the
 * current user and on the current machine using
 * Java's Preference objects.  Failing that, default setting values come from
 * Settings.properties file.  If both of those fail, default values come from
 * static arrays defined in this class.  The latter can can be modified prior to
 * instantiating Settings object.
 * <p>
 * NOTE: If the Preference objects fail due to security exceptions, changes to
 * settings will not carry over from one MARS session to the next.
 * <p>
 * Actual implementation of the Preference objects is platform-dependent.
 * For Windows, they are stored in Registry.  To see, run regedit and browse to:
 * HKEY_CURRENT_USER\Software\JavaSoft\Prefs\mars
 *
 * @author Pete Sanderson
 **/

public class Settings extends Observable {
    private static final String settingsFile = "Settings";

    /**
     * Flag to determine whether or not program being assembled is limited to
     * basic MIPS instructions and formats.
     */
    public static final int EXTENDED_ASSEMBLER_ENABLED = 0;

    /**
     * Flag to determine whether or not program being assembled is limited to
     * using register numbers instead of names. NOTE: Its default value is
     * false and the IDE provides no means to change it!
     */
    public static final int BARE_MACHINE_ENABLED = 1;

    /**
     * Flag to determine whether or not a file is immediately and automatically assembled
     * upon opening. Handy when using externa editor like mipster.
     */
    public static final int ASSEMBLE_ON_OPEN_ENABLED = 2;

    /**
     * Flag to determine whether only the current editor source file (enabled false) or
     * all files in its directory (enabled true) will be assembled when assembly is selected.
     */
    public static final int ASSEMBLE_ALL_ENABLED = 3;

    /**
     * Default visibilty of label window (symbol table).  Default only, dynamic status
     * maintained by ExecutePane
     */
    public static final int LABEL_WINDOW_VISIBILITY = 4;

    /**
     * Default setting for displaying addresses and values in hexidecimal in the Execute
     * pane.
     */
    public static final int DISPLAY_ADDRESSES_IN_HEX = 5;

    public static final int DISPLAY_VALUES_IN_HEX = 6;

    /**
     * Flag to determine whether the currently selected exception handler source file will
     * be included in each assembly operation.
     */
    public static final int EXCEPTION_HANDLER_ENABLED = 7;

    /**
     * Flag to determine whether or not delayed branching is in effect at MIPS execution.
     * This means we simulate the pipeline and statement FOLLOWING a successful branch
     * is executed before branch is taken. DPS 14 June 2007.
     */
    public static final int DELAYED_BRANCHING_ENABLED = 8;

    /**
     * Flag to determine whether or not assembler warnings are considered errors.
     */
    public static final int WARNINGS_ARE_ERRORS = 10;

    /**
     * Flag to determine whether or not to display and use program arguments
     */
    public static final int PROGRAM_ARGUMENTS = 11;

    /**
     * Flag to control whether or not highlighting is applied to data segment window
     */
    public static final int DATA_SEGMENT_HIGHLIGHTING = 12;

    /**
     * Flag to control whether or not highlighting is applied to register windows
     */
    public static final int REGISTERS_HIGHLIGHTING = 13;

    /**
     * Flag to control whether or not assembler automatically initializes program counter to 'main's address
     */
    public static final int START_AT_MAIN = 14;

    /**
     * Flag to control whether or not simulator will use popup dialog for input syscalls
     */
    public static final int POPUP_SYSCALL_INPUT = 17;

    /**
     * Flag to determine whether a program can write binary code to the text or data segment and
     * execute that code.
     */
    public static final int SELF_MODIFYING_CODE_ENABLED = 20;

    private static final String[] booleanSettingsKeys = {"ExtendedAssembler", "BareMachine", "AssembleOnOpen", "AssembleAll", "LabelWindowVisibility", "DisplayAddressesInHex", "DisplayValuesInHex", "LoadExceptionHandler", "DelayedBranching", "EditorLineNumbersDisplayed", "WarningsAreErrors", "ProgramArguments", "DataSegmentHighlighting", "RegistersHighlighting", "StartAtMain", "EditorCurrentLineHighlighting", "PopupInstructionGuidance", "PopupSyscallInput", "GenericTextEditor", "AutoIndent", "SelfModifyingCode"};

    /**
     * Last resort default values for boolean settings; will use only  if neither
     * the Preferences nor the properties file work. If you wish to change them,
     * do so before instantiating the Settings object.
     * Values are matched to keys by list position.
     */
    public static final boolean[] defaultBooleanSettingsValues = {
            true, false, false, false, false, true, true, false, false, true, false, false, true, true, false, true, true, false, false, true, false};

    /**
     * Current specified exception handler file (a MIPS assembly source file)
     */
    public static final int EXCEPTION_HANDLER = 0;

    /**
     * Order of text segment table columns
     */
    public static final int TEXT_COLUMN_ORDER = 1;

    /**
     * State for sorting label window display
     */
    public static final int LABEL_SORT_STATE = 2;

    /**
     * Identifier of current memory configuration
     */
    public static final int MEMORY_CONFIGURATION = 3;

    private static final String[] stringSettingsKeys = {"ExceptionHandler", "TextColumnOrder", "LabelSortState", "MemoryConfiguration", "CaretBlinkRate", "EditorTabSize", "EditorPopupPrefixLength"};

    /**
     * Last resort default values for String settings;
     * will use only if neither the Preferences nor the properties file work.
     * If you wish to change, do so before instantiating the Settings object.
     * Must match key by list position.
     */
    private static final String[] defaultStringSettingsValues = {"", "0 1 2 3 4", "0", "", "500", "8", "2"};

    private final boolean[] booleanSettingsValues;
    private final String[] stringSettingsValues;

    private final Preferences preferences;

    /**
     * Create Settings object and set to saved values.  If saved values not found, will set
     * based on defaults stored in Settings.properties file.  If file problems, will set based
     * on defaults stored in this class.
     */
    public Settings() {
        this(true);
    }

    /**
     * Create Settings object and set to saved values.  If saved values not found, will set
     * based on defaults stored in Settings.properties file.  If file problems, will set based
     * on defaults stored in this class.
     *
     * @param gui true if running the graphical IDE, false if running from command line.
     *            Ignored as of release 3.6 but retained for compatability.
     */

    public Settings(boolean gui) {
        booleanSettingsValues = new boolean[booleanSettingsKeys.length];
        stringSettingsValues = new String[stringSettingsKeys.length];
        // This determines where the values are actually stored.  Actual implementation
        // is platform-dependent.  For Windows, they are stored in Registry.  To see,
        // run regedit and browse to: HKEY_CURRENT_USER\Software\JavaSoft\Prefs\mars
        preferences = Preferences.userNodeForPackage(this.getClass());
        // The gui parameter, formerly passed to initialize(), is no longer needed
        // because I removed (1/21/09) the call to generate the Font object for the text editor.
        // Font objects are now generated only on demand so the "if (gui)" guard
        // is no longer necessary.  Originally added by Berkeley b/c they were running it on a 
        // headless server and running in command mode.  The Font constructor resulted in Swing 
        // initialization which caused problems.  Now this will only occur on demand from
        // Venus, which happens only when running as GUI.
        initialize();
    }

    /**
     * Return whether backstepping is permitted at this time.  Backstepping is ability to undo execution
     * steps one at a time.  Available only in the IDE.  This is not a persistent setting and is not under
     * MARS user control.
     *
     * @return true if backstepping is permitted, false otherwise.
     */
    public boolean getBackSteppingEnabled() {
        return (Globals.program != null && Globals.program.getBackStepper() != null && Globals.program.getBackStepper().enabled());
    }

    /**
     * Reset settings to default values, as described in the constructor comments.
     *
     * @param gui true if running from GUI IDE and false if running from command mode.
     *            Ignored as of release 3.6 but retained for compatibility.
     */
    public void reset(boolean gui) {
        initialize();
    }

    // *********************************************************************************

    ////////////////////////////////////////////////////////////////////////
    //  Setting Getters
    ////////////////////////////////////////////////////////////////////////   	

    /**
     * Fetch value of a boolean setting given its identifier.
     *
     * @param id int containing the setting's identifier (constants listed above)
     * @return corresponding boolean setting.
     * @throws IllegalArgumentException if identifier is invalid.
     */
    public boolean getBooleanSetting(int id) {
        System.out.println("getBooleanSetting(" + id + ")");
        if (id >= 0 && id < booleanSettingsValues.length) {
            return booleanSettingsValues[id];
        } else {
            throw new IllegalArgumentException("Invalid boolean setting ID");
        }
    }

    /**
     * Name of currently selected exception handler file.
     *
     * @return String pathname of current exception handler file, empty if none.
     */
    public String getExceptionHandler() {
        return stringSettingsValues[EXCEPTION_HANDLER];
    }

    /**
     * Returns identifier of current built-in memory configuration.
     *
     * @return String identifier of current built-in memory configuration, empty if none.
     */
    public String getMemoryConfiguration() {
        return stringSettingsValues[MEMORY_CONFIGURATION];
    }

    ////////////////////////////////////////////////////////////////////////
    //  Setting Setters
    ////////////////////////////////////////////////////////////////////////

    /**
     * Set value of a boolean setting given its id and the value.
     *
     * @param id    int containing the setting's identifier (constants listed above)
     * @param value boolean value to store
     * @throws IllegalArgumentException if identifier is not valid.
     */
    public void setBooleanSetting(int id, boolean value) {
        if (id >= 0 && id < booleanSettingsValues.length) {
            internalSetBooleanSetting(id, value);
        } else {
            throw new IllegalArgumentException("Invalid boolean setting ID");
        }
    }

    /**
     * Temporarily establish boolean setting.  This setting will NOT be written to persisent
     * store!  Currently this is used only when running MARS from the command line
     *
     * @param id    setting identifier.  These are defined for this class as static final int.
     * @param value True to enable the setting, false otherwise.
     */
    public void setBooleanSettingNonPersistent(int id, boolean value) {
        if (id >= 0 && id < booleanSettingsValues.length) {
            booleanSettingsValues[id] = value;
        } else {
            throw new IllegalArgumentException("Invalid boolean setting ID");
        }
    }

    /**
     * Set name of exception handler file and write it to persistent storage.
     *
     * @param newFilename name of exception handler file
     */
    public void setExceptionHandler(String newFilename) {
        setStringSetting(EXCEPTION_HANDLER, newFilename);
    }

    /**
     * Store the identifier of the memory configuration.
     *
     * @param config A string that identifies the current built-in memory configuration
     */

    public void setMemoryConfiguration(String config) {
        setStringSetting(MEMORY_CONFIGURATION, config);
    }

    /**
     * Initialize settings to default values.
     * Strategy: First set from properties file.
     * If that fails, set from the array.
     * In either case, use these values as defaults in call to Preferences.
     */
    private void initialize() {
        applyDefaultSettings();
        if (!readSettingsFromPropertiesFile(settingsFile))
            System.out.println("MARS System error: unable to read Settings.properties defaults. Using built-in defaults.");
        getSettingsFromPreferences();
    }

    /**
     * Default values.  Will be replaced if available from property file or Preferences object.
     */
    private void applyDefaultSettings() {
        System.arraycopy(defaultBooleanSettingsValues, 0, booleanSettingsValues, 0, booleanSettingsValues.length);
        System.arraycopy(defaultStringSettingsValues, 0, stringSettingsValues, 0, stringSettingsValues.length);
    }

    /**
     * Used by all the boolean setting "setter" methods.
     */
    private void internalSetBooleanSetting(int settingIndex, boolean value) {
        if (value != booleanSettingsValues[settingIndex]) {
            booleanSettingsValues[settingIndex] = value;
            saveBooleanSetting(settingIndex);
            setChanged();
            notifyObservers();
        }
    }

    /**
     * Used by setter method(s) for string-based settings (initially, only exception handler name)
     */
    private void setStringSetting(int settingIndex, String value) {
        stringSettingsValues[settingIndex] = value;
        saveStringSetting(settingIndex);
    }

    /**
     * Uses linear search of the settings array.
     * Not a huge deal as settings are little-used.
     * @return index or -1 if not found.
     */
    private int getIndexOfKey(String key, String[] array) {
        int index = -1;
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(key)) {
                index = i;
                break;
            }
        }
        return index;
    }

    /**
     * Establish the settings from the given properties file.
     * @return true if it worked, false if it didn't.
     * Note the properties file exists only to provide default values in case the Preferences fail or have not been
     * recorded yet.
     * <p>
     * Any settings successfully read will be stored in both the xSettingsValues and defaultXSettingsValues arrays
     * (x = boolean, string, color).
     * The latter will overwrite the last-resort default values hardcoded into the arrays above.
     * <p>
     * NOTE: If there is NO ENTRY for the specified property, Globals.getPropertyEntry() returns null.
     * This is no cause for alarm.
     * It will occur during system development or upon the first use of a new MARS release in which new settings have
     * been defined.
     * In that case, this method will NOT make an assignment to the settings array!
     * So consider it a precondition of this method: the settings arrays must already be initialized with last-resort
     * default values.
     */
    private boolean readSettingsFromPropertiesFile(String filename) {
        String settingValue;
        try {
            for (int i = 0; i < booleanSettingsKeys.length; i++) {
                settingValue = Globals.getPropertyEntry(filename, booleanSettingsKeys[i]);
                if (settingValue != null)
                    booleanSettingsValues[i] = defaultBooleanSettingsValues[i] = Boolean.parseBoolean(settingValue);
            }
            for (int i = 0; i < stringSettingsKeys.length; i++) {
                settingValue = Globals.getPropertyEntry(filename, stringSettingsKeys[i]);
                if (settingValue != null) stringSettingsValues[i] = defaultStringSettingsValues[i] = settingValue;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * Get settings values from the Preferences object.
     * A key-value pair will only be written to Preferences if/when the value is modified.
     * If it has not been modified, the default value will be returned here.
     * PRECONDITION: Values arrays have already been initialized to default values from
     * Settings.properties file or default value arrays above!
     */
    private void getSettingsFromPreferences() {
        for (int i = 0; i < booleanSettingsKeys.length; i++) {
            booleanSettingsValues[i] = preferences.getBoolean(booleanSettingsKeys[i], booleanSettingsValues[i]);
        }
        for (int i = 0; i < stringSettingsKeys.length; i++) {
            stringSettingsValues[i] = preferences.get(stringSettingsKeys[i], stringSettingsValues[i]);
        }
    }

    /**
     * Save the key-value pair in the Properties object and assure it is written to persistent storage.
     */
    private void saveBooleanSetting(int index) {
        try {
            preferences.putBoolean(booleanSettingsKeys[index], booleanSettingsValues[index]);
            preferences.flush();
        } catch (SecurityException se) {
            // cannot write to persistent storage for security reasons
        } catch (BackingStoreException bse) {
            // unable to communicate with persistent storage (strange days)
        }
    }

    /**
     * Save the key-value pair in the Properties object and assure it is written to persistent storage.
     */
    private void saveStringSetting(int index) {
        try {
            preferences.put(stringSettingsKeys[index], stringSettingsValues[index]);
            preferences.flush();
        } catch (SecurityException se) {
            // cannot write to persistent storage for security reasons
        } catch (BackingStoreException bse) {
            // unable to communicate with persistent storage (strange days)
        }
    }
}