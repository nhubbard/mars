package edu.missouristate.mars.cli

import edu.missouristate.mars.api.MarsGuiProxy
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/** Console services for headless command-line execution. */
class HeadlessGuiProxy : MarsGuiProxy {
  private val stdin = BufferedReader(InputStreamReader(System.`in`))

  override val isGuiMode = false

  override fun postMessage(message: String) = System.err.print(message)

  override fun postRunMessage(message: String) = print(message)

  override fun readInput(maxLength: Int): String {
    return try {
      val line = stdin.readLine() ?: return ""
      if (maxLength < 0) line else line.take(maxLength)
    } catch (_: IOException) {
      ""
    }
  }

  override fun readInputWithPrompt(prompt: String): String {
    System.err.print(prompt)
    return readInput(-1)
  }

  override fun clearRunConsole() = Unit
}
