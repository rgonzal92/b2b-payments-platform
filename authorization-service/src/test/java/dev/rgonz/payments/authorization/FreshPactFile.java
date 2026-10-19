package dev.rgonz.payments.authorization;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Deletes a consumer's pact file once per test run, before the first of its consumer test classes,
 * so the run writes it fresh. Pact JVM merges each class's interactions into an existing file, so
 * an interaction removed or renamed in the tests would otherwise stay in the committed pact, where
 * CI's check for a rewritten pact can't see it. Its {@code pact.writer.overwrite} isn't the fix:
 * each class would overwrite the one before. Running only some of the consumer classes writes a
 * file with only theirs.
 */
public final class FreshPactFile implements BeforeAllCallback {

  private static final Set<Path> DELETED = ConcurrentHashMap.newKeySet();

  private final Path file;

  public FreshPactFile(String consumer, String provider) {
    this.file =
        Path.of(System.getProperty("pact.rootDir", "target/pacts"))
            .resolve(consumer + "-" + provider + ".json")
            .toAbsolutePath()
            .normalize();
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    if (DELETED.add(file)) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
