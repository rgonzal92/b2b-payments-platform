package dev.rgonz.payments.platform.clearing;

import static dev.rgonz.payments.platform.clearing.TestClearingFiles.newId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.rgonz.payments.platform.TestAuthorizationsTopic;
import dev.rgonz.payments.platform.TestKafka;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.clearing.TestClearingFiles.Detail;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A Clearing File waits in the drop folder, untouched, while the platform's Authorization Copy lags
 * behind the {@code authorizations} topic or Kafka can't be reached, so it never matches against a
 * copy missing an approved Authorization; once the copy has caught up, it imports as before. On a
 * real Kafka in Testcontainers, with the platform's listeners running.
 */
@Import({TestcontainersConfiguration.class, TestKafka.class})
@SpringBootTest(
    properties = {
      "payables.payment-worker.enabled=false",
      "cards.expiry-job.enabled=false",
      "spring.kafka.listener.auto-startup=true"
    })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ClearingFileWaitsForAuthorizationCopyTests {

  // In the platform's 990000 range, but 19 digits: the vault issues 16, so no card has it.
  private static final String UNKNOWN_PAN = "9900000000000000000";
  private static final String COPY_GROUP = "platform-authorization";
  private static final Path DROP_FOLDER = newDropFolder();

  @DynamicPropertySource
  static void dropFolder(DynamicPropertyRegistry registry) {
    registry.add("clearing.drop-folder", DROP_FOLDER::toString);
  }

  @Autowired ClearingFileImports imports;
  @Autowired ClearingFiles files;
  @Autowired KafkaContainer kafka;
  @Autowired KafkaListenerEndpointRegistry listeners;
  @Autowired MockMvcTester mvc;

  @Test
  void aFileWaitsWhileTheCopyLagsAndImportsOnceItHasCaughtUp(CapturedOutput output)
      throws Exception {
    var topic = new TestAuthorizationsTopic(kafka);
    var copyListener = copyListener();
    copyListener.stop();
    String fileName;
    String fileId = newId("F", 16);
    try {
      // An event the copy's consumer hasn't read: whatever its type, the copy lags until it has.
      topic.send("card-token-1", "CardOnFile", "{\"cardToken\":\"card-token-1\"}");
      fileName = dropOneClearing(fileId, 31);
      var before = Files.readAllBytes(DROP_FOLDER.resolve(fileName));

      imports.importNewFiles();

      assertThat(DROP_FOLDER.resolve(fileName)).exists().hasBinaryContent(before);
      assertThat(DROP_FOLDER.resolve("processing").resolve(fileName)).doesNotExist();
      assertThat(files.find(fileId)).isEmpty();
      assertThat(output).contains("Clearing File " + fileName + " waits");
    } finally {
      copyListener.start();
    }
    topic.awaitConsumed(COPY_GROUP);

    imports.importNewFiles();

    assertThat(DROP_FOLDER.resolve(fileName)).doesNotExist();
    assertThat(files.find(fileId))
        .hasValueSatisfying(
            file -> {
              assertThat(file.status()).isEqualTo(ClearingFileSummary.Status.IMPORTED);
              assertThat(file.outcomes()).containsEntry(ClearingOutcome.UNKNOWN_CARD, 1L);
            });
  }

  // An import that stopped left its file in processing/: its restart waits there the same way, and
  // the drop-folder check restarts it once the copy has caught up.
  @Test
  void aRestartInProcessingWaitsWhileTheCopyLagsAndTheCheckImportsItOnceCaughtUp(
      CapturedOutput output) throws Exception {
    var topic = new TestAuthorizationsTopic(kafka);
    var fileId = newId("F", 16);
    var fileName = dropOneClearing(fileId, 33);
    var processing = DROP_FOLDER.resolve("processing");
    Files.createDirectories(processing);
    Files.move(DROP_FOLDER.resolve(fileName), processing.resolve(fileName));
    var copyListener = copyListener();
    copyListener.stop();
    try {
      topic.send("card-token-2", "CardOnFile", "{\"cardToken\":\"card-token-2\"}");

      imports.importNewFiles();

      assertThat(processing.resolve(fileName)).exists();
      assertThat(files.find(fileId)).isEmpty();
      assertThat(output).contains("Clearing File " + fileName + " waits");
    } finally {
      copyListener.start();
    }
    topic.awaitConsumed(COPY_GROUP);

    imports.importNewFiles();

    assertThat(processing.resolve(fileName)).doesNotExist();
    assertThat(files.find(fileId))
        .hasValueSatisfying(
            file -> assertThat(file.status()).isEqualTo(ClearingFileSummary.Status.IMPORTED));
  }

  @Test
  void aFileWaitsWhileKafkaIsDownAndTheImportAnswersWithinSeconds(CapturedOutput output) {
    var fileId = newId("F", 16);
    var fileName = dropOneClearing(fileId, 32);
    var docker = kafka.getDockerClient();
    docker.pauseContainerCmd(kafka.getContainerId()).exec();
    try {
      // The drop-folder check leaves it where it is.
      imports.importNewFiles();

      assertThat(DROP_FOLDER.resolve(fileName)).exists();
      assertThat(files.find(fileId)).isEmpty();
      assertThat(output).contains("Clearing File " + fileName + " waits");

      // So does an import through the endpoint, answering within seconds.
      var started = Instant.now();

      var response =
          mvc.post()
              .uri("/v1/clearing-files")
              .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_issuer-admin")))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"fileName\":\"" + fileName + "\"}")
              .exchange();

      assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(15));
      assertThat(response).hasStatus(503);
      assertThat(response.getResponse().getContentType()).isEqualTo("application/problem+json");
      assertThat(DROP_FOLDER.resolve(fileName)).exists();
      assertThat(files.find(fileId)).isEmpty();
    } finally {
      docker.unpauseContainerCmd(kafka.getContainerId()).exec();
    }
  }

  private MessageListenerContainer copyListener() {
    return listeners.getListenerContainers().stream()
        .filter(container -> COPY_GROUP.equals(container.getGroupId()))
        .findFirst()
        .orElseThrow();
  }

  private static String dropOneClearing(String fileId, long sequenceNumber) {
    var clearing =
        new Detail(
            newId("R", 20), UNKNOWN_PAN, 500_000, newId("Q", 12), "ZZZZZZ", "GLOBEX000000001");
    return TestClearingFiles.drop(DROP_FOLDER, fileId, sequenceNumber, List.of(clearing));
  }

  private static Path newDropFolder() {
    try {
      return Files.createTempDirectory("clearing-drop-folder");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
