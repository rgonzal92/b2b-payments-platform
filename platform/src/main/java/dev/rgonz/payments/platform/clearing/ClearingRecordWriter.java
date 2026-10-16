package dev.rgonz.payments.platform.clearing;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/** The import step's writer: imports each Clearing of a chunk, in the chunk's transaction. */
class ClearingRecordWriter implements ItemWriter<ClearingRecord> {

  private final String fileId;
  private final Clearings clearings;

  ClearingRecordWriter(String fileId, Clearings clearings) {
    this.fileId = fileId;
    this.clearings = clearings;
  }

  @Override
  public void write(Chunk<? extends ClearingRecord> chunk) {
    for (var clearing : chunk) {
      clearings.importClearing(fileId, clearing);
    }
  }
}
