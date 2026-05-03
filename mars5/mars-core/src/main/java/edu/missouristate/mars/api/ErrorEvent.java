package edu.missouristate.mars.api;

/**
 * Carries information about a run-time error that caused simulation to halt.
 *
 * @param message human-readable error description
 * @param cause the underlying exception, if available
 */
public record ErrorEvent(String message, Throwable cause) {}
