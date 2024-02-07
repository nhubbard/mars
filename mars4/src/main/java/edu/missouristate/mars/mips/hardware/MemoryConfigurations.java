package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.util.ExcludeFromJacocoGeneratedReport;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Models the collection of MIPS memory configurations.
 * The default configuration is based on SPIM.  Starting with MARS 3.7,
 * the configuration can be changed.
 *
 * @author Pete Sanderson
 * @version August 2009
 */


public class MemoryConfigurations {
    static ArrayList<MemoryConfiguration> configurations = null;
    static MemoryConfiguration defaultConfiguration;
    static MemoryConfiguration currentConfiguration;

    // Be careful, these arrays are parallel and position-sensitive.
    // The getters in this and in MemoryConfiguration depend on this
    // sequence.  Should be refactored...  The order comes from the
    // original listed order in Memory.java, where most of these were
    // "final" until Mars 3.7 and changeable memory configurations.
    private static final String[] configurationItemNames = {
            ".text base address",
            "data segment base address",
            ".extern base address",
            "global pointer $gp",
            ".data base address",
            "heap base address",
            "stack pointer $sp",
            "stack base address",
            "user space high address",
            "kernel space base address",
            ".ktext base address",
            "exception handler address",
            ".kdata base address",
            "MMIO base address",
            "kernel space high address",
            "data segment limit address",
            "text limit address",
            "kernel data segment limit address",
            "kernel text limit address",
            "stack limit address",
            "memory map limit address"
    };

    // Default configuration comes from SPIM
    private static final int[] defaultConfigurationItemValues = {
            0x00400000, // .text Base Address
            0x10000000, // Data Segment base address
            0x10000000, // .extern Base Address
            0x10008000, // Global Pointer $gp)
            0x10010000, // .data base Address
            0x10040000, // heap base address
            0x7fffeffc, // stack pointer $sp (from SPIM not MIPS)
            0x7ffffffc, // stack base address
            0x7fffffff, // highest address in user space
            0x80000000, // lowest address in kernel space
            0x80000000, // .ktext base address
            0x80000180, // exception handler address
            0x90000000, // .kdata base address
            0xffff0000, // MMIO base address
            0xffffffff, // highest address in kernel (and memory)
            0x7fffffff, // data segment limit address
            0x0ffffffc, // text limit address
            0xfffeffff, // kernel data segment limit address
            0x8ffffffc, // kernel text limit address
            0x10040000, // stack limit address
            0xffffffff  // memory map limit address
    };

    // Compact allows 16 bit addressing, data segment starts at 0
    private static final int[] dataBasedCompactConfigurationItemValues = {
            0x00003000, // .text Base Address
            0x00000000, // Data Segment base address
            0x00001000, // .extern Base Address
            0x00001800, // Global Pointer $gp)
            0x00000000, // .data base Address
            0x00002000, // heap base address
            0x00002ffc, // stack pointer $sp
            0x00002ffc, // stack base address
            0x00003fff, // highest address in user space
            0x00004000, // lowest address in kernel space
            0x00004000, // .ktext base address
            0x00004180, // exception handler address
            0x00005000, // .kdata base address
            0x00007f00, // MMIO base address
            0x00007fff, // highest address in kernel (and memory)
            0x00002fff, // data segment limit address
            0x00003ffc, // text limit address
            0x00007eff, // kernel data segment limit address
            0x00004ffc, // kernel text limit address
            0x00002000, // stack limit address
            0x00007fff  // memory map limit address
    };

    // Compact allows 16 bit addressing, text segment starts at 0
    private static final int[] textBasedCompactConfigurationItemValues = {
            0x00000000, // .text Base Address
            0x00001000, // Data Segment base address
            0x00001000, // .extern Base Address
            0x00001800, // Global Pointer $gp)
            0x00002000, // .data base Address
            0x00003000, // heap base address
            0x00003ffc, // stack pointer $sp
            0x00003ffc, // stack base address
            0x00003fff, // highest address in user space
            0x00004000, // lowest address in kernel space
            0x00004000, // .ktext base address
            0x00004180, // exception handler address
            0x00005000, // .kdata base address
            0x00007f00, // MMIO base address
            0x00007fff, // highest address in kernel (and memory)
            0x00003fff, // data segment limit address
            0x00000ffc, // text limit address
            0x00007eff, // kernel data segment limit address
            0x00004ffc, // kernel text limit address
            0x00003000, // stack limit address
            0x00007fff  // memory map limit address
    };

    @ExcludeFromJacocoGeneratedReport
    private MemoryConfigurations() {}

    public static void buildConfigurationCollection() {
        if (configurations == null) {
            configurations = new ArrayList<>();
            configurations.add(new MemoryConfiguration("Default", "Default", configurationItemNames, defaultConfigurationItemValues));
            configurations.add(new MemoryConfiguration("CompactDataAtZero", "Compact, Data at Address 0", configurationItemNames, dataBasedCompactConfigurationItemValues));
            configurations.add(new MemoryConfiguration("CompactTextAtZero", "Compact, Text at Address 0", configurationItemNames, textBasedCompactConfigurationItemValues));
            defaultConfiguration = configurations.get(0);
            currentConfiguration = defaultConfiguration;
            // Get current config from settings
            setCurrentConfiguration(getConfigurationByName(Globals.getSettings().getMemoryConfiguration()));
        }
    }

    public static Iterator<MemoryConfiguration> getConfigurationsIterator() {
        if (configurations == null) {
            buildConfigurationCollection();
        }
        return configurations.iterator();
    }

    public static MemoryConfiguration getConfigurationByName(String name) {
        Iterator<MemoryConfiguration> configurationsIterator = getConfigurationsIterator();
        while (configurationsIterator.hasNext()) {
            MemoryConfiguration config = configurationsIterator.next();
            if (name.equals(config.getConfigurationIdentifier())) {
                return config;
            }
        }
        return null;
    }

    public static MemoryConfiguration getDefaultConfiguration() {
        if (defaultConfiguration == null) {
            buildConfigurationCollection();
        }
        return defaultConfiguration;
    }

    public static MemoryConfiguration getCurrentConfiguration() {
        if (currentConfiguration == null) {
            buildConfigurationCollection();
        }
        return currentConfiguration;
    }

    public static boolean setCurrentConfiguration(MemoryConfiguration config) {
        if (config == null)
            return false;
        if (config != currentConfiguration) {
            currentConfiguration = config;
            Globals.memory.clear();
            RegisterFile.getUserRegister("$gp").changeResetValue(config.getGlobalPointer());
            RegisterFile.getUserRegister("$sp").changeResetValue(config.getStackPointer());
            RegisterFile.getProgramCounterRegister().changeResetValue(config.getTextBaseAddress());
            RegisterFile.initializeProgramCounter(config.getTextBaseAddress());
            RegisterFile.resetRegisters();
            return true;
        } else {
            return false;
        }
    }

    ////  Use these to initialize Memory static variables at launch

    public static int getDefaultTextBaseAddress() {
        return defaultConfigurationItemValues[0];
    }

    public static int getDefaultDataSegmentBaseAddress() {
        return defaultConfigurationItemValues[1];
    }

    public static int getDefaultExternBaseAddress() {
        return defaultConfigurationItemValues[2];
    }

    public static int getDefaultGlobalPointer() {
        return defaultConfigurationItemValues[3];
    }

    public static int getDefaultDataBaseAddress() {
        return defaultConfigurationItemValues[4];
    }

    public static int getDefaultHeapBaseAddress() {
        return defaultConfigurationItemValues[5];
    }

    public static int getDefaultStackPointer() {
        return defaultConfigurationItemValues[6];
    }

    public static int getDefaultStackBaseAddress() {
        return defaultConfigurationItemValues[7];
    }

    public static int getDefaultUserHighAddress() {
        return defaultConfigurationItemValues[8];
    }

    public static int getDefaultKernelBaseAddress() {
        return defaultConfigurationItemValues[9];
    }

    public static int getDefaultKernelTextBaseAddress() {
        return defaultConfigurationItemValues[10];
    }

    public static int getDefaultExceptionHandlerAddress() {
        return defaultConfigurationItemValues[11];
    }

    public static int getDefaultKernelDataBaseAddress() {
        return defaultConfigurationItemValues[12];
    }

    public static int getDefaultMemoryMapBaseAddress() {
        return defaultConfigurationItemValues[13];
    }

    public static int getDefaultKernelHighAddress() {
        return defaultConfigurationItemValues[14];
    }
}