package dev.rgonz.payments.platform.authorization;

/**
 * The Issuer's approve-or-decline answer to a Charge: an ISO 8583 response code (DE 39) and, only
 * on an approval, an authorization code (DE 38); null on a decline.
 */
record Authorization(ResponseCode responseCode, String authorizationCode) {

  static Authorization approved(String authorizationCode) {
    return new Authorization(ResponseCode.APPROVED, authorizationCode);
  }

  static Authorization declined(ResponseCode responseCode) {
    return new Authorization(responseCode, null);
  }

  Status status() {
    return responseCode == ResponseCode.APPROVED ? Status.APPROVED : Status.DECLINED;
  }

  enum ResponseCode {
    APPROVED("00"),
    /** The amount isn't positive. */
    INVALID_AMOUNT("13"),
    /** No card the Issuer issued has the PAN. */
    INVALID_CARD("14"),
    /**
     * The card can't be charged: it's cancelled or expired, or the request's expiry isn't the
     * card's or has passed.
     */
    EXPIRED_CARD("54"),
    /** The card already has an approved Authorization. */
    NOT_PERMITTED("57"),
    /** The amount is above the card's, or in another currency. */
    EXCEEDS_AMOUNT("61"),
    /** The security code isn't the card's. */
    SECURITY_CODE_MISMATCH("N7");

    private final String code;

    ResponseCode(String code) {
      this.code = code;
    }

    String code() {
      return code;
    }

    /** The response code with this DE 39 value, such as one recorded earlier. */
    static ResponseCode of(String code) {
      for (var responseCode : values()) {
        if (responseCode.code.equals(code)) {
          return responseCode;
        }
      }
      throw new IllegalArgumentException("No response code " + code + ".");
    }
  }

  enum Status {
    APPROVED,
    DECLINED
  }
}
