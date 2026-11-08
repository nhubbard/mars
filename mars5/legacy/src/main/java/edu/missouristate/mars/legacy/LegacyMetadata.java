package edu.missouristate.mars.legacy;

import edu.missouristate.mars.util.PropertiesFile;
import java.util.*;

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Metadata and resource defaults for the existing frontend. */
public final class LegacyMetadata {
  private LegacyMetadata() {}

  private static final String configPropertiesFile = "Config";
  public static final String version = "4.6",
      imagesPath = "/images/",
      helpPath = "/help/",
      userInputAlert = "**** user input : ";
  public static final String copyrightYears = "2003-2014",
      copyrightHolders = "Pete Sanderson and Kenneth Vollmar";
  public static final ArrayList<String> fileExtensions = getFileExtensions();
  public static final int maximumMessageCharacters = getMessageLimit(),
      maximumErrorMessages = getErrorLimit(),
      maximumBacksteps = getBackstepLimit();
  public static final String ASCII_NON_PRINT = getAsciiNonPrint();
  public static final String[] ASCII_TABLE = getAsciiStrings();

  // Read byte limit of Run I/O or MARS Messages text to buffer.
  private static int getMessageLimit() {
    return getIntegerProperty(configPropertiesFile, "MessageLimit", 1000000);
  }

  // Read limit on number of error messages produced by one assemble operation.
  private static int getErrorLimit() {
    return getIntegerProperty(configPropertiesFile, "ErrorLimit", 200);
  }

  // Read backstep limit (number of operations to buffer) from properties file.
  private static int getBackstepLimit() {
    return getIntegerProperty(configPropertiesFile, "BackstepLimit", 1000);
  }

  // Read ASCII default display character for non-printing characters, from properties file.
  public static String getAsciiNonPrint() {
    String anp = getPropertyEntry(configPropertiesFile, "AsciiNonPrint");
    return (anp == null) ? "." : ((anp.equals("space")) ? " " : anp);
  }

  // Read ASCII strings for codes 0-255, from properties file. If string
  // value is "null", substitute value of ASCII_NON_PRINT.  If string is
  // "space", substitute string containing one space character.
  public static String[] getAsciiStrings() {
    String let = getPropertyEntry(configPropertiesFile, "AsciiTable");
    String placeHolder = getAsciiNonPrint();
    String[] lets = let.split(" +");
    int maxLength = 0;
    for (int i = 0; i < lets.length; i++) {
      if (lets[i].equals("null")) {
        lets[i] = placeHolder;
      }
      if (lets[i].equals("space")) {
        lets[i] = " ";
      }
      if (lets[i].length() > maxLength) {
        maxLength = lets[i].length();
      }
    }
    String padding = "        ";
    maxLength++;
    for (int i = 0; i < lets.length; i++) {
      lets[i] = padding.substring(0, maxLength - lets[i].length()) + lets[i];
    }
    return lets;
  }

  // Read and return integer property value for given file and property name.
  // Default value is returned if property file or name not found.
  private static int getIntegerProperty(
      String propertiesFile, String propertyName, int defaultValue) {
    int limit = defaultValue; // just in case no entry is found
    Properties properties = PropertiesFile.loadPropertiesFromFile(propertiesFile);
    try {
      limit =
          Integer.parseInt(properties.getProperty(propertyName, Integer.toString(defaultValue)));
    } catch (NumberFormatException ignored) {
    } // do nothing, I already have a default
    return limit;
  }

  // Read assembly language file extensions from properties file.  Resulting
  // string is tokenized into array list (assume StringTokenizer default delimiters).
  private static ArrayList<String> getFileExtensions() {
    ArrayList<String> extensionsList = new ArrayList<>();
    String extensions = getPropertyEntry(configPropertiesFile, "Extensions");
    if (extensions != null) {
      StringTokenizer st = new StringTokenizer(extensions);
      while (st.hasMoreTokens()) {
        extensionsList.add(st.nextToken());
      }
    }
    return extensionsList;
  }

  /**
   * Get list of MarsTools that reside outside the MARS distribution. Currently this is done by
   * adding the tool's path name to the list of values for the external_tools property. Use ";" as
   * delimiter!
   *
   * @return ArrayList. Each item is file path to .class file of a class that implements MarsTool.
   *     If none, returns empty list.
   */
  public static ArrayList<String> getExternalTools() {
    ArrayList<String> toolsList = new ArrayList<>();
    String delimiter = ";";
    String tools = getPropertyEntry(configPropertiesFile, "ExternalTools");
    if (tools != null) {
      StringTokenizer st = new StringTokenizer(tools, delimiter);
      while (st.hasMoreTokens()) {
        toolsList.add(st.nextToken());
      }
    }
    return toolsList;
  }

  /**
   * Read and return property file value (if any) for requested property.
   *
   * @param propertiesFile name of properties file (do NOT include filename extension, which is
   *     assumed to be ".properties")
   * @param propertyName String containing desired property name
   * @return String containing associated value; null if property not found
   */
  public static String getPropertyEntry(String propertiesFile, String propertyName) {
    return PropertiesFile.loadPropertiesFromFile(propertiesFile).getProperty(propertyName);
  }
}
