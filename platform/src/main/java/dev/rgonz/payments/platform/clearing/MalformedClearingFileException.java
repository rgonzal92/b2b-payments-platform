package dev.rgonz.payments.platform.clearing;

/**
 * A Clearing File that doesn't follow the layout. The message names the line and the field, never
 * what the field holds: a detail record holds a PAN, and this message reaches logs and the job
 * repository (ADR-0004).
 */
class MalformedClearingFileException extends RuntimeException {

  MalformedClearingFileException(int lineNumber, String problem) {
    super("Line " + lineNumber + ": " + problem + ".");
  }
}
