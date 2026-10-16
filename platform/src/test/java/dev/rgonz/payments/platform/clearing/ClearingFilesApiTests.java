package dev.rgonz.payments.platform.clearing;

import static dev.rgonz.payments.platform.clearing.TestClearingFiles.newId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Issuer staff read the Clearing Files the platform received over HTTP, on PostgreSQL: newest
 * first, a page at a time, with the gaps in the Network's sequence numbers. The files are imported
 * through the endpoint, without Clearings, as the list doesn't depend on them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ClearingFilesApiTests {

  private static final String ISSUER_ADMIN = "ROLE_issuer-admin";
  private static final Path DROP_FOLDER = newDropFolder();

  @DynamicPropertySource
  static void dropFolder(DynamicPropertyRegistry registry) {
    registry.add("clearing.drop-folder", DROP_FOLDER::toString);
  }

  @Autowired MockMvcTester mvc;
  @Autowired JdbcClient jdbc;

  // The list and its gaps span every file in the database, so each test starts with none: this
  // class's context has a database of its own.
  @BeforeEach
  void noFiles() {
    jdbc.sql("truncate clearing.clearing_file, clearing.clearing, clearing.settlement").update();
  }

  @Test
  void filesAreListedNewestFirstAPageAtATime() throws Exception {
    var oldest = imported(1);
    var middle = imported(2);
    var newest = imported(3);

    var firstPage = list("limit=2", ISSUER_ADMIN);

    assertThat(firstPage).hasStatusOk();
    assertThat(firstPage)
        .bodyJson()
        .extractingPath("$.items[*].id")
        .asArray()
        .containsExactly(newest, middle);
    String cursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");
    assertThat(cursor).isNotBlank();

    var lastPage = list("limit=2&cursor=" + cursor, ISSUER_ADMIN);

    assertThat(lastPage).hasStatusOk();
    assertThat(lastPage)
        .bodyJson()
        .extractingPath("$.items[*].id")
        .asArray()
        .containsExactly(oldest);
    assertThat(lastPage).bodyJson().doesNotHavePath("$.nextCursor");
  }

  @Test
  void aPageOfNoFilesOrMoreThanAHundredOrACursorTheListDidntGiveIsABadRequest() {
    for (var query : List.of("limit=0", "limit=101", "cursor=not-a-cursor")) {
      assertProblem(list(query, ISSUER_ADMIN), 400);
    }
  }

  @Test
  void aSequenceNumberNoImportedFileHasBetweenTheLowestAndTheHighestIsMissing() {
    imported(1);
    imported(2);
    imported(4);

    var response = list("limit=20", ISSUER_ADMIN);

    assertThat(response).hasStatusOk();
    assertThat(response)
        .bodyJson()
        .extractingPath("$.missingSequenceNumbers")
        .asArray()
        .containsExactly(3);
  }

  @Test
  void noSequenceNumberIsMissingWhenTheImportedFilesFollowOnFromEachOther() {
    imported(1);
    imported(2);
    imported(3);

    var response = list("limit=20", ISSUER_ADMIN);

    assertThat(response).hasStatusOk();
    assertThat(response).bodyJson().extractingPath("$.missingSequenceNumbers").asArray().isEmpty();
  }

  @Test
  void aRefusedFileIsListedWithWhyAndItsSequenceNumberStaysMissing() {
    imported(1);
    var refusedId = newId("F", 16);
    // A header and nothing after it: no trailer.
    var refused =
        TestClearingFiles.write(DROP_FOLDER, List.of(TestClearingFiles.header(refusedId, 2)));
    assertProblem(importFile(refused), 422);
    imported(3);

    var response = list("limit=20", ISSUER_ADMIN);

    assertThat(response).hasStatusOk();
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"items": [{}, {"id": "%s", "sequenceNumber": 2, "status": "REFUSED",
                            "refusalReason": "Line 1: the file ends without a trailer (92).",
                            "recordCount": null}, {}],
             "missingSequenceNumbers": [2]}
            """
                .formatted(refusedId));
  }

  @Test
  void oneFileShowsTheSummaryItsImportAnswered() {
    var fileId = imported(7);

    var response = file(fileId, ISSUER_ADMIN);

    assertThat(response).hasStatusOk();
    assertThat(response)
        .bodyJson()
        .isEqualTo(
            """
            {"id": "%s", "sequenceNumber": 7, "businessDate": "2026-10-15", "status": "IMPORTED",
             "recordCount": 0, "skippedCount": 0,
             "outcomes": {"matched": 0, "aboveAuthorizedAmount": 0, "noAuthorization": 0,
                          "unknownCard": 0, "otherCurrency": 0, "againstReversedAuthorization": 0,
                          "afterHoldExpired": 0, "authorizationAlreadyCleared": 0},
             "settlement": {"amount": {"minorUnits": 0, "currency": "USD"},
                            "importedTotal": {"minorUnits": 0, "currency": "USD"},
                            "matched": true}}
            """
                .formatted(fileId));
  }

  @Test
  void aFileIdNoFileHasIsNotFound() {
    var response = file("NO-SUCH-FILE", ISSUER_ADMIN);

    assertProblem(response, 404);
  }

  @Test
  void onlyIssuerStaffMayReadTheFiles() {
    var fileId = imported(1);

    assertProblem(list("limit=20", "ROLE_erp"), 403);
    assertProblem(file(fileId, "ROLE_approver"), 403);
  }

  private static void assertProblem(MvcTestResult response, int status) {
    assertThat(response).hasStatus(status);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo(status);
  }

  private MvcTestResult file(String fileId, String authority) {
    return mvc.get()
        .uri("/v1/clearing-files/{fileId}", fileId)
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .exchange();
  }

  // A file without Clearings, imported through the endpoint; returns its file ID.
  private String imported(long sequenceNumber) {
    var fileId = newId("F", 16);
    var fileName = TestClearingFiles.drop(DROP_FOLDER, fileId, sequenceNumber, List.of());
    assertThat(importFile(fileName)).hasStatus(201);
    return fileId;
  }

  private MvcTestResult importFile(String fileName) {
    return mvc.post()
        .uri("/v1/clearing-files")
        .with(jwt().authorities(new SimpleGrantedAuthority(ISSUER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"fileName": "%s"}
            """
                .formatted(fileName))
        .exchange();
  }

  private MvcTestResult list(String query, String authority) {
    return mvc.get()
        .uri("/v1/clearing-files?" + query)
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .exchange();
  }

  private static Path newDropFolder() {
    try {
      return Files.createTempDirectory("clearing-drop-folder");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
