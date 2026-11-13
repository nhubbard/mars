package edu.missouristate.mars.api

/** Thread-safe console and input services provided by the CLI or IntelliJ frontend. */
interface MarsGuiProxy {
    // Third-party frontends can choose to handle exit status themselves.
    @get:Suppress("SameReturnValue")
    val isGuiMode: Boolean

    /** Post to the assembler and simulator messages console. */
    fun postMessage(message: String)

    /** Post program output. */
    fun postRunMessage(message: String)

    /** Read program input. A limit of -1 reads the full line. */
    fun readInput(maxLength: Int): String

    /** Show a prompt and read program input. */
    fun readInputWithPrompt(prompt: String): String

    fun clearRunConsole()
}
