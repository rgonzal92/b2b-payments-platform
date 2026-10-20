package dev.rgonz.payments.vault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Card Vault as its own app (ADR-0018): it holds every card's PAN and security code, encrypted
 * under a data key of the card's own that OpenBao Transit wraps, so the platform needs neither.
 * Nothing calls it yet; the platform's issue step will (#135).
 */
@SpringBootApplication
public class VaultServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(VaultServiceApplication.class, args);
  }
}
