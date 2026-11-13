package edu.missouristate.mars

import edu.missouristate.mars.util.PropertiesFile
import java.util.StringTokenizer
import java.util.regex.Pattern

/** Application defaults shared by the assembler, simulator, and frontends. */
object ApplicationInfo {
    private const val configPropertiesFile = "Config"
    const val version = "4.6"

    private val spaces = Pattern.compile(" +")

    @JvmField val fileExtensions: ArrayList<String> = tokenize(getPropertyEntry(configPropertiesFile, "Extensions"))
    @JvmField val maximumErrorMessages = integerProperty("ErrorLimit", 200)
    @JvmField val maximumBacksteps = integerProperty("BackstepLimit", 1_000)
    @JvmField val ASCII_NON_PRINT = getAsciiNonPrint()
    @JvmField val ASCII_TABLE = getAsciiStrings()

    @JvmStatic
    fun getAsciiNonPrint(): String = when (val placeholder = getPropertyEntry(configPropertiesFile, "AsciiNonPrint")) {
        null -> "."
        "space" -> " "
        else -> placeholder
    }

    @JvmStatic
    fun getAsciiStrings(): Array<String> {
        val placeholder = getAsciiNonPrint()
        val table = spaces.split(getPropertyEntry(configPropertiesFile, "AsciiTable"))
        var longest = 0
        for (index in table.indices) {
            var cell = table[index]
            if (cell == "null") cell = placeholder
            if (cell == "space") cell = " "
            table[index] = cell
            longest = maxOf(longest, cell.length)
        }
        val padding = "        "
        for (index in table.indices) {
            val cell = table[index]
            table[index] = padding.substring(0, longest + 1 - cell.length) + cell
        }
        return table
    }

    private fun integerProperty(name: String, default: Int): Int =
        getPropertyEntry(configPropertiesFile, name)?.toIntOrNull() ?: default

    private fun tokenize(value: String?): ArrayList<String> {
        val values = ArrayList<String>()
        if (value == null) return values
        val tokens = StringTokenizer(value)
        while (tokens.hasMoreTokens()) values.add(tokens.nextToken())
        return values
    }

    @JvmStatic
    fun getPropertyEntry(propertiesFile: String?, propertyName: String): String? =
        PropertiesFile.loadPropertiesFromFile(propertiesFile).getProperty(propertyName)
}
