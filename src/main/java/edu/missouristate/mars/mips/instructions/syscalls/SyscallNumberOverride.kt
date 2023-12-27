package edu.missouristate.mars.mips.instructions.syscalls

import kotlin.system.exitProcess

/**
 * Represents User override of default syscall number assignment.
 * Such overrides are specified in the config.txt file read when
 * MARS starts up.
 */
class SyscallNumberOverride(val name: String, value: String) {
    /**
     * Get the new service number as an int.
     *
     * @return the service number
     */
    var number: Int = 0

    /**
     * Constructor is called with two strings: service name and desired
     * number.
     * Will throw an exception if the number is malformed, but does
     * not check the validity of the service name or number.
     *
     * @param serviceName a String containing syscall service mnemonic.
     * @param value       a String containing its reassigned syscall service number.
     * If this number is previously assigned to a different syscall which does not
     * also receive a new number, then an error for duplicate numbers will
     * be issued at MARS launch.
     */
    init {
        try {
            this.number = value.trim { it <= ' ' }.toInt()
        } catch (e: NumberFormatException) {
            println("Error processing Syscall number override: '${value.trim { it <= ' ' }}' is not a valid integer")
            exitProcess(0)
        }
    }
}



