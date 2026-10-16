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
    /**
     * Do not honour: the Network reversed the request ID before the platform answered it, so it
     * already declined the Charge.
     */
    DO_NOT_HONOUR("05"),
    /** The amount isn't positive. */
    INVALID_AMOUNT("13"),
    /** No card the Issuer issued has the PAN. */
    INVALID_CARD("14"),
    /**
     * The card can't be charged: it's cancelled or expired, or the request's expiry isn't the
     * card's or has passed.
     */
    EXPIRED_CARD("54"),
    /**
     * Not permitted: the Charge's Acceptor ID isn't the card's Supplier's (or its Supplier has
     * none), or the card already has an approved Authorization.
     */
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
    DECLINED,
    /**
     * The Network cancelled it with a Reversal. A reversed approval keeps the {@code 00} it was
     * given; a request ID reversed before its Authorization arrived has {@code 05} and no card.
     */
    REVERSED
  }
}
