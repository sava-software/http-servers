package software.sava.http_servers.core.handlers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/// Replays the handlerUtil fuzz seed corpus, asserting the differential harness invariant:
/// the hand-rolled scanner and the naive reference agree on every committed input.
final class HandlerUtilFuzzRegressionTests {

  @Test
  void corpusInputsAgreeWithTheReference() throws IOException, URISyntaxException {
    final var corpus = Path.of(HandlerUtilFuzzRegressionTests.class.getResource("/fuzz/handlerUtil").toURI());
    try (final Stream<Path> files = Files.list(corpus)) {
      files.forEach(file -> {
        try {
          HandlerUtilFuzz.fuzzerTestOneInput(Files.readAllBytes(file));
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    }
  }
}
