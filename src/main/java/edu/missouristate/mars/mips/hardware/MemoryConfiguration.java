package edu.missouristate.mars.mips.hardware;

/**
 * Models the memory configuration for the simulated MIPS machine.
 * "configuration" refers to the starting memory addresses for
 * the various memory segments.
 * The default configuration is based on SPIM.  Starting with MARS 3.7,
 * the configuration can be changed.
 *
 * @author Pete Sanderson
 * @version August 2009
 */


public class MemoryConfiguration {
    // Identifier is used for saving setting; name is used for display
    private String configurationIdentifier, configurationName;
    private String[] configurationItemNames;
    private int[] configurationItemValues;


    public MemoryConfiguration(String ident, String name, String[] items, int[] values) {
        this.configurationIdentifier = ident;
        this.configurationName = name;
        this.configurationItemNames = items;
        this.configurationItemValues = values;
    }

    public String getConfigurationIdentifier() {
        return configurationIdentifier;
    }

    public String getConfigurationName() {
        return configurationName;
    }

    public int[] getConfigurationItemValues() {
        return configurationItemValues;
    }

    public String[] getConfigurationItemNames() {
        return configurationItemNames;
    }

    public int getTextBaseAddress() {
        return configurationItemValues[0];
    }

    public int getDataSegmentBaseAddress() {
        return configurationItemValues[1];
    }

    public int getExternBaseAddress() {
        return configurationItemValues[2];
    }

    public int getGlobalPointer() {
        return configurationItemValues[3];
    }

    public int getDataBaseAddress() {
        return configurationItemValues[4];
    }

    public int getHeapBaseAddress() {
        return configurationItemValues[5];
    }

    public int getStackPointer() {
        return configurationItemValues[6];
    }

    public int getStackBaseAddress() {
        return configurationItemValues[7];
    }

    public int getUserHighAddress() {
        return configurationItemValues[8];
    }

    public int getKernelBaseAddress() {
        return configurationItemValues[9];
    }

    public int getKernelTextBaseAddress() {
        return configurationItemValues[10];
    }

    public int getExceptionHandlerAddress() {
        return configurationItemValues[11];
    }

    public int getKernelDataBaseAddress() {
        return configurationItemValues[12];
    }

    public int getMemoryMapBaseAddress() {
        return configurationItemValues[13];
    }

    public int getKernelHighAddress() {
        return configurationItemValues[14];
    }

    public int getDataSegmentLimitAddress() {
        return configurationItemValues[15];
    }

    public int getTextLimitAddress() {
        return configurationItemValues[16];
    }

    public int getKernelDataSegmentLimitAddress() {
        return configurationItemValues[17];
    }

    public int getKernelTextLimitAddress() {
        return configurationItemValues[18];
    }

    public int getStackLimitAddress() {
        return configurationItemValues[19];
    }

    public int getMemoryMapLimitAddress() {
        return configurationItemValues[20];
    }

}