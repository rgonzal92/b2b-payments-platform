package dev.rgonz.payments.platform.clearing;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;

/**
 * The import step's reader: a Clearing File's detail records, one Clearing per item, after its
 * header and up to its trailer. Spring Batch saves how many it read with each committed chunk, and
 * a restart skips that many. Not Spring Batch's {@code FlatFileItemReader}, whose parse errors
 * quote the whole line, PAN included; this one's errors name the line and field only (ADR-0004).
 */
class ClearingRecordReader extends AbstractItemCountingItemStreamItemReader<ClearingRecord> {

  private final Path file;
  private BufferedReader lines;
  private int lineNumber;

  ClearingRecordReader(Path file) {
    this.file = file;
    setName("clearingRecordReader");
  }

  @Override
  protected void doOpen() throws IOException {
    lines = Files.newBufferedReader(file, StandardCharsets.US_ASCII);
    ClearingFileLayout.readHeader(lines);
    lineNumber = 1;
  }

  // The validate step checked the whole file, so a trailer ends it.
  @Override
  protected ClearingRecord doRead() throws IOException {
    var line = lines.readLine();
    if (line == null) {
      return null;
    }
    lineNumber++;
    return switch (ClearingFileLayout.parse(line, lineNumber)) {
      case ClearingRecord clearing -> clearing;
      case FileTrailer _ -> null;
      case FileHeader _ -> throw new MalformedClearingFileException(lineNumber, "a second header");
    };
  }

  @Override
  protected void doClose() throws IOException {
    if (lines != null) {
      lines.close();
    }
  }
}
