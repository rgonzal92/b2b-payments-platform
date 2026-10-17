package dev.rgonz.payments.platform.reconciliation;

import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * Raises each Break a step found, and counts the new ones in the step's execution context ({@link
 * #NEW_BREAKS}), saved with each chunk: Spring Batch's write count is every Break found, so the
 * known ones are the difference.
 */
class BreakWriter implements ItemWriter<Break> {

  static final String NEW_BREAKS = "newBreaks";

  private final Breaks breaks;
  private final StepExecution stepExecution;

  BreakWriter(Breaks breaks, StepExecution stepExecution) {
    this.breaks = breaks;
    this.stepExecution = stepExecution;
  }

  @Override
  public void write(Chunk<? extends Break> found) {
    var raised = found.getItems().stream().filter(breaks::raise).count();
    var context = stepExecution.getExecutionContext();
    context.putLong(NEW_BREAKS, context.getLong(NEW_BREAKS, 0) + raised);
  }
}
