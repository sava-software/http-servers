package software.sava.http_servers.sava.x402;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Replays the svmVerify fuzz seed corpus — including inputs that previously crashed the
/// verifier — asserting the total-function contract: any byte sequence produces a
/// [VerifyResponse], never a throwable.
final class VerifyFuzzRegressionTest {

  @Test
  void corpusInputsNeverThrow() throws IOException, URISyntaxException {
    final var corpus = Path.of(VerifyFuzzRegressionTest.class.getResource("/fuzz/svmVerify").toURI());
    try (final Stream<Path> files = Files.list(corpus)) {
      files.forEach(file -> {
        final byte[] data;
        try {
          data = Files.readAllBytes(file);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
        assertNotNull(SvmExactVerifyFuzz.REQUIREMENTS);
        SvmExactVerifyFuzz.fuzzerTestOneInput(data);
      });
    }
  }
}
