package edu.missouristate.mars.api

/** Selects instruction availability and integer register width for a MARS session. */
enum class MarsTarget {
  MARS32,
  MARS64;

  companion object {
    val DEFAULT = MARS64
  }
}
