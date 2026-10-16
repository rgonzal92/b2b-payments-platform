package dev.rgonz.payments.platform.clearing;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * The import step's writer: imports each Clearing of a chunk, and counts the file's skipped ones,
 * in the chunk's transaction.
 */
class ClearingRecordWriter implements ItemWriter<ClearingRecord> {

  private final String fileId;
  private final Clearings clearings;
  private final ClearingFiles files;

  ClearingRecordWriter(String fileId, Clearings clearings, ClearingFiles files) {
    this.fileId = fileId;
    this.clearings = clearings;
    this.files = files;
  }

  @Override
  public void write(Chunk<? extends ClearingRecord> chunk) {
    var skipped = 0;
    for (var clearing : chunk) {
      if (!clearings.importClearing(fileId, clearing)) {
        skipped++;
      }
    }
    if (skipped > 0) {
      files.countSkipped(fileId, skipped);
    }
  }
}
