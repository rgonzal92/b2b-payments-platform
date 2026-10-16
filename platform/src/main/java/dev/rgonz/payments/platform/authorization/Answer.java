package dev.rgonz.payments.platform.authorization;

/**
 * The platform's answer to a Charge: an ISO 8583 response code (DE 39) and, only on an approval, an
 * authorization code (DE 38); null on a decline.
 */
record Answer(ResponseCode responseCode, String authorizationCode) {

  static Answer approved(String authorizationCode) {
    return new Answer(ResponseCode.APPROVED, authorizationCode);
  }

  static Answer declined(ResponseCode responseCode) {
    return new Answer(responseCode, null);
  }

  Status status() {
    return responseCode == ResponseCode.APPROVED ? Status.APPROVED : Status.DECLINED;
  }

  enum ResponseCode {
    APPROVED("00"),
    /** No card the Issuer issued has the PAN. */
    INVALID_CARD("14"),
    /** The card can't be charged: it isn't ACTIVE (#31 adds the expiry checks). */
    EXPIRED_CARD("54");

    private final String code;

    ResponseCode(String code) {
      this.code = code;
    }

    String code() {
      return code;
    }
  }

  enum Status {
    APPROVED,
    DECLINED
  }
}
