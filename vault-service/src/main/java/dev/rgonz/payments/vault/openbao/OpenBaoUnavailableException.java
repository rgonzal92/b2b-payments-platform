package dev.rgonz.payments.vault.openbao;

/** OpenBao didn't answer, or answered that it can't serve now (sealed, or a server error). */
public class OpenBaoUnavailableException extends RuntimeException {

  OpenBaoUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
