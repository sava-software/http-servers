package software.sava.http_servers.fusionauth.logging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/// The shim maps java-http's logger SPI onto the already-hardened BaseJulLogger; what is
/// asserted here is the mapping itself — SPI level to JUL level in both directions, and
/// every emit method reaching the JUL logger the wrapped class name resolves to.
final class FusionAuthJulLoggerTests {

  /// Distinct marker classes so each test gets an isolated JUL logger in the global registry.
  private static final class LevelMarker {
  }

  private static final class EmitMarker {
  }

  private static final class GateMarker {
  }

  private record Captured(io.fusionauth.http.log.Logger logger, java.util.logging.Logger jul, List<LogRecord> records) {
  }

  private static Captured captured(final Class<?> marker) {
    final var logger = new FusionAuthJulLoggerFactory().getLogger(marker);
    final var jul = java.util.logging.Logger.getLogger(marker.getName());
    jul.setUseParentHandlers(false);
    final var records = new ArrayList<LogRecord>();
    jul.addHandler(new Handler() {
      @Override
      public void publish(final LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    });
    return new Captured(logger, jul, records);
  }

  @Test
  void setLevelMapsEverySpiLevelOntoJul() {
    final var captured = captured(LevelMarker.class);
    final var logger = captured.logger;

    logger.setLevel(io.fusionauth.http.log.Level.Trace);
    assertEquals(java.util.logging.Level.FINER, captured.jul.getLevel());
    assertTrue(logger.isTraceEnabled());
    assertTrue(logger.isDebugEnabled());

    logger.setLevel(io.fusionauth.http.log.Level.Debug);
    assertEquals(java.util.logging.Level.FINE, captured.jul.getLevel());
    assertFalse(logger.isTraceEnabled());
    assertTrue(logger.isDebugEnabled());

    logger.setLevel(io.fusionauth.http.log.Level.Info);
    assertEquals(java.util.logging.Level.INFO, captured.jul.getLevel());
    assertFalse(logger.isDebugEnabled());
    assertTrue(logger.isInfoEnabled());

    logger.setLevel(io.fusionauth.http.log.Level.Error);
    assertEquals(java.util.logging.Level.SEVERE, captured.jul.getLevel());
    assertFalse(logger.isInfoEnabled());
    assertTrue(logger.isErrorEnabled());

    // a null level must leave the previous mapping in place
    logger.setLevel(null);
    assertEquals(java.util.logging.Level.SEVERE, captured.jul.getLevel());
  }

  @Test
  void everyEmitMethodReachesJulAtItsMappedLevel() {
    final var captured = captured(EmitMarker.class);
    final var logger = captured.logger;
    logger.setLevel(io.fusionauth.http.log.Level.Trace);

    logger.trace("t");
    logger.trace("t {}", 1);
    logger.debug("d");
    logger.debug("d {}", 2);
    final var debugFailure = new IllegalStateException("debug boom");
    logger.debug("d-t", debugFailure);
    logger.info("i");
    logger.info("i {}", 3);
    logger.error("e");
    final var errorFailure = new IllegalStateException("error boom");
    logger.error("e-t", errorFailure);

    final var summary = captured.records.stream()
        .map(r -> r.getLevel() + ":" + r.getMessage() + (r.getThrown() == null ? "" : "!"))
        .toList();
    assertEquals(List.of(
        "FINER:t", "FINER:t 1",
        "FINE:d", "FINE:d 2", "FINE:d-t!",
        "INFO:i", "INFO:i 3",
        "SEVERE:e", "SEVERE:e-t!"
    ), summary);
    assertSame(debugFailure, captured.records.get(4).getThrown());
    assertSame(errorFailure, captured.records.getLast().getThrown());
  }

  @Test
  void disabledLevelsEmitNothing() {
    final var captured = captured(GateMarker.class);
    final var logger = captured.logger;
    logger.setLevel(io.fusionauth.http.log.Level.Error);

    logger.trace("t");
    logger.debug("d");
    logger.info("i");
    assertTrue(captured.records.isEmpty(), "below-threshold levels must not publish");

    logger.error("e");
    assertEquals(1, captured.records.size());
  }
}
