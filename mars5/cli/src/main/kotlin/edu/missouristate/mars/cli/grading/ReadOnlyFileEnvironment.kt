package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.SyscallEnvironment
import java.util.Map.copyOf

/**
 * One execution's deterministic lesson input. All file operations use the same in-memory
 * implementation as grading; unrelated services retain the supplied frontend's behavior. Neither
 * successful nor failed file operations can reach the host filesystem. Unlike grading, interactive
 * launches do not retain an unbounded operation history.
 *
 * Create a fresh instance for each launch and close it after simulation stops. Closing releases
 * fixture contents and descriptors; it never closes [delegate]. The definition's maxRequest is a
 * grading assertion, not an artificial limit on the guest's requests.
 */
class ReadOnlyFileEnvironment(
    private val delegate: SyscallEnvironment,
    definition: FunctionFileReadCheck,
) : SyscallEnvironment by delegate, AutoCloseable {
  private var files: GradingFiles? =
      GradingFiles(recordAccesses = false).also {
        it.configure(
            definition.copy(additionalFiles = copyOf(definition.additionalFiles)).decoded(),
            definition.maxReadSize,
            definition.failAfterBytes,
            definition.firstDescriptor,
        )
      }

  private fun files(): GradingFiles = checkNotNull(files) { "Lesson file environment is closed" }

  override fun allowsSyscall(number: Int): Boolean =
      when (number) {
        13,
        14,
        16 -> {
          files()
          true
        }
        15 -> {
          files()
          false
        }
        else -> delegate.allowsSyscall(number)
      }

  override fun openFile(path: String?, flags: Int): Int = files().open(path, flags)

  override fun readFromFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int =
      files().read(fd, buffer, lengthRequested)

  override fun writeToFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int =
      files().write(fd, buffer, lengthRequested)

  override fun closeFile(fd: Int) = files().close(fd)

  override fun close() {
    files = null
  }
}
