package edu.missouristate.mars.util

import java.io.IOException
import java.util.*

/**
 * Provides means to work with ".properties" files which are used to store
 * various MARS settings.
 *
 * @author Pete Sanderson
 * @version October 2006
 */
object PropertiesFile {
    /**
     * Produce Properties (a Hashtable) object containing key-value pairs
     * from specified properties file.  This may be used as an alternative
     * to readPropertiesFile() which uses a different implementation.
     *
     * @param file The properties file name.
     * Do NOT include the file extension as it is assumed to be ".properties" and is added here.
     * @return Properties (Hashtable) of key-value pairs read from the file.
     */
    @JvmStatic
    fun loadPropertiesFromFile(file: String): Properties {
        val properties = Properties()
        try {
            val `is` = PropertiesFile::class.java.getResourceAsStream("/$file.properties")
            properties.load(`is`)
        } catch (ignored: IOException) {}

        return properties
    }
}

