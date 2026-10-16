package dev.rgonz.payments.platform.clearing;

/**
 * A Clearing File that fails a check of the import's validate step: it doesn't follow the layout,
 * its trailer's totals aren't its records', or its currency isn't one the platform settles. The
 * import refuses the whole file. The message names the line and the field, never what the field
 * holds: a detail record holds a PAN, and this message reaches logs, the job repository and the
 * file's refusal reason (ADR-0004).
 */
class MalformedClearingFileException extends RuntimeException {

  MalformedClearingFileException(int lineNumber, String problem) {
    super("Line " + lineNumber + ": " + problem + ".");
  }
}
