package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.shared.BuyerId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each Buyer's Idempotency-Keys, with a hash of the request a key first came with and the response
 * it got, so a resent request gets the same response instead of acting twice.
 */
@Repository
class IdempotentRequests {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  IdempotentRequests(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Runs the action the first time the Buyer sends this key and stores its response; later sends of
   * the same key and request get that stored response.
   */
  @Transactional
  <T> ResponseEntity<T> once(
      BuyerId buyerId,
      String key,
      Object request,
      Class<T> responseType,
      Supplier<ResponseEntity<T>> action) {
    var requestHash = sha256(json.writeValueAsBytes(request));
    // A concurrent request with the same key waits on this insert until the first one ends.
    var claimed =
        jdbc.sql(
                    """
                    insert into payables.idempotent_request (buyer_id, idempotency_key, request_hash)
                    values (:buyerId, :key, :hash)
                    on conflict (buyer_id, idempotency_key) do nothing
                    """)
                .param("buyerId", buyerId.value())
                .param("key", key)
                .param("hash", requestHash)
                .update()
            == 1;
    if (claimed) {
      var response = action.get();
      jdbc.sql(
              """
              update payables.idempotent_request
                 set response_status = :status, response_body = :body
               where buyer_id = :buyerId and idempotency_key = :key
              """)
          .param("status", response.getStatusCode().value())
          .param("body", json.writeValueAsString(response.getBody()))
          .param("buyerId", buyerId.value())
          .param("key", key)
          .update();
      return response;
    }
    var earlier =
        jdbc.sql(
                """
                select request_hash = :hash as same_request, response_status, response_body
                  from payables.idempotent_request
                 where buyer_id = :buyerId and idempotency_key = :key
                """)
            .param("hash", requestHash)
            .param("buyerId", buyerId.value())
            .param("key", key)
            .query(
                (row, rowNumber) ->
                    new Earlier(
                        row.getBoolean("same_request"),
                        row.getInt("response_status"),
                        row.getString("response_body")))
            .single();
    if (!earlier.sameRequest()) {
      throw new ErrorResponseException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          ProblemDetail.forStatusAndDetail(
              HttpStatus.UNPROCESSABLE_CONTENT,
              "This Idempotency-Key was already used for a different request."),
          null);
    }
    return ResponseEntity.status(earlier.responseStatus())
        .body(json.readValue(earlier.responseBody(), responseType));
  }

  private record Earlier(boolean sameRequest, int responseStatus, String responseBody) {}

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      // Every Java runtime must provide SHA-256.
      throw new IllegalStateException(e);
    }
  }
}
