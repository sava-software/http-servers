package software.sava.http_servers.core.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/// Replays the formatPlaceholders fuzz seed corpus, asserting the harness invariants:
/// no throw on arbitrary messages, and generated token streams substitute exactly as the
/// token semantics dictate.
final class FormatPlaceholdersFuzzRegressionTests {

  @Test
  void corpusInputsHoldTheHarnessInvariants() throws IOException, URISyntaxException {
    final var corpus = Path.of(FormatPlaceholdersFuzzRegressionTests.class.getResource("/fuzz/formatPlaceholders").toURI());
    try (final Stream<Path> files = Files.list(corpus)) {
      files.forEach(file -> {
        try {
          FormatPlaceholdersFuzz.fuzzerTestOneInput(Files.readAllBytes(file));
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    }
  }
}
