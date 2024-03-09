package edu.missouristate.mars.util;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Provides means to work with ".properties" files which are used to store
 * various MARS settings.
 *
 * @author Pete Sanderson
 * @version October 2006
 */
public class PropertiesFile {

    /**
     * Produce Properties (a Hashtable) object containing key-value pairs
     * from specified properties file.  This may be used as an alternative
     * to readPropertiesFile() which uses a different implementation.
     *
     * @param file Properties filename.  Do NOT include the file extension as
     *             it is assumed to be ".properties" and is added here.
     * @return Properties (Hashtable) of key-value pairs read from the file.
     */

    public static @NotNull Properties loadPropertiesFromFile(String file) {
        Properties properties = new Properties();
        try {
            InputStream is = PropertiesFile.class.getResourceAsStream("/" + file + ".properties");
            properties.load(is);
        } catch (IOException | NullPointerException ignored) {
        } // If it doesn't work, properties will be empty

        return properties;
    }
}

