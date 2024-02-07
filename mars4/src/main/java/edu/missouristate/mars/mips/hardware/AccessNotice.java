package edu.missouristate.mars.mips.hardware;

/**
 * Object provided to Observers of runtime access to MIPS memory or registers.
 * The access types READ and WRITE defined here; use subclasses defined for
 * MemoryAccessNotice and RegisterAccessNotice.  This is abstract class.
 *
 * @author Pete Sanderson
 * @version July 2005
 */


public abstract class AccessNotice {
    /**
     * Indicates the purpose of access was to read.
     */
    public static final int READ = 0;
    /**
     * Indicates the purpose of access was to write.
     */
    public static final int WRITE = 1;

    private final int accessType;
    private final Thread thread;

    protected AccessNotice(int type) {
        if (type != READ && type != WRITE) {
            throw new IllegalArgumentException();
        }
        accessType = type;
        thread = Thread.currentThread();
    }

    /**
     * Get the access type: READ or WRITE.
     *
     * @return Access type, either {@code AccessNotice.READ} or {@code AccessNotice.WRITE}
     */
    public int getAccessType() {
        return accessType;
    }

    /**
     * Get reference to the thread that created this notice
     *
     * @return Return reference to the thread that created this notice.
     */
    public Thread getThread() {
        return thread;
    }

    /**
     * Query whether the access originated from MARS GUI (AWT event queue)
     *
     * @return true if this access originated from MARS GUI, false otherwise
     */
    // 'A' is the first character of the main AWT event queue thread name.
    // "AWT-EventQueue-0"
    public boolean accessIsFromGUI() {
        return thread.getName().startsWith("AWT");
    }

    /**
     * Query whether the access originated from executing MIPS program
     *
     * @return true if this access originated from executing MIPS program, false otherwise
     */
    // Thread to execute the MIPS program is instantiated in SwingWorker.java.
    // There it is given the name "MIPS" to replace the default "Thread-x".
    public boolean accessIsFromMIPS() {
        return thread.getName().startsWith("MIPS");
    }

}