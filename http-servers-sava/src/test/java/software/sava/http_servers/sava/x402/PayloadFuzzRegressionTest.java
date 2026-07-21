package software.sava.http_servers.sava.x402;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/// Replays the x402Payload fuzz seed corpus through the full harness, asserting its
/// invariants hold for every committed input: parsers tolerate malformed input with a
/// RuntimeException, the direct-JSON and Base64-header paths agree, and the gate answers
/// with a 402 or the protected 200, never a throwable.
final class PayloadFuzzRegressionTest {

  @Test
  void corpusInputsHoldTheHarnessInvariants() throws IOException, URISyntaxException {
    final var corpus = Path.of(PayloadFuzzRegressionTest.class.getResource("/fuzz/x402Payload").toURI());
    try (final Stream<Path> files = Files.list(corpus)) {
      files.forEach(file -> {
        final byte[] data;
        try {
          data = Files.readAllBytes(file);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
        X402PayloadFuzz.fuzzerTestOneInput(data);
      });
    }
  }
}
