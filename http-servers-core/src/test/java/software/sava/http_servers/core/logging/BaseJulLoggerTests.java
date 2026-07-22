package software.sava.http_servers.core.logging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.http_servers.core.logging.BaseJulLogger.formatPlaceholders;
import static software.sava.http_servers.core.logging.BaseJulLogger.stringify;

final class BaseJulLoggerTests {

  // formatPlaceholders

  @Test
  void substitutesPlaceholdersInOrder() {
    assertEquals("a=1 b=2", formatPlaceholders("a={} b={}", 1, 2));
  }

  @Test
  void messageWithoutBracesIsReturnedIdentically() {
    final var message = "plain message";
    assertSame(message, formatPlaceholders(message, 1, 2), "the no-brace fast path must not copy");
  }

  @Test
  void exhaustedValuesLeaveThePlaceholderIntact() {
    assertEquals("a=1 b={}", formatPlaceholders("a={} b={}", 1));
  }

  @Test
  void extraValuesAreIgnored() {
    assertEquals("a=1", formatPlaceholders("a={}", 1, 2, 3));
  }

  @Test
  void escapedPlaceholderIsEmittedLiterally() {
    assertEquals("a={} b=1", formatPlaceholders("a=\\{} b={}", 1));
  }

  @Test
  void loneBackslashIsPreserved() {
    assertEquals("a\\b", formatPlaceholders("a\\b{}", new Object[]{""}).substring(0, 3));
    assertEquals("tail\\", formatPlaceholders("tail\\", 1));
  }

  @Test
  void openBraceWithoutCloseIsLiteral() {
    assertEquals("a{b", formatPlaceholders("a{b", 1));
    assertEquals("trailing{", formatPlaceholders("trailing{", 1));
  }

  @Test
  void nullValueRendersAsNullText() {
    assertEquals("v=null", formatPlaceholders("v={}", new Object[]{null}));
  }

  @Test
  void placeholderAtTheVeryStartIsSubstituted() {
    assertEquals("1 first", formatPlaceholders("{} first", 1));
  }

  @Test
  void backslashBeforeTheFinalCharacterIsPreserved() {
    // the '{' forces the slow path; the trailing backslash probes the escape look-ahead bound
    assertEquals("1 tail\\", formatPlaceholders("{} tail\\", 1));
  }

  @Test
  void closeBraceAfterAnOrdinaryCharacterIsLiteral() {
    assertEquals("end}", formatPlaceholders("end}{}", ""));
  }

  // stringify

  @Test
  void stringifyRendersScalarsAndNull() {
    assertEquals("null", stringify(null));
    assertEquals("42", stringify(42));
    assertEquals("text", stringify("text"));
  }

  @Test
  void stringifyRendersEveryPrimitiveArrayType() {
    assertEquals("[1, 2]", stringify(new int[]{1, 2}));
    assertEquals("[1, 2]", stringify(new long[]{1L, 2L}));
    assertEquals("[1.5]", stringify(new double[]{1.5}));
    assertEquals("[1.5]", stringify(new float[]{1.5f}));
    assertEquals("[true, false]", stringify(new boolean[]{true, false}));
    assertEquals("[1, 2]", stringify(new byte[]{1, 2}));
    assertEquals("[1, 2]", stringify(new short[]{1, 2}));
    assertEquals("[a, b]", stringify(new char[]{'a', 'b'}));
  }

  @Test
  void stringifyDeepRendersObjectArrays() {
    assertEquals("[[a], [b]]", stringify(new Object[]{new Object[]{"a"}, new Object[]{"b"}}));
  }

  // emission through a capturing JUL handler

  private static final class TestLogger extends BaseJulLogger {
    private TestLogger() {
      super(TestLogger.class);
    }

    void info(final String message) {
      log(Level.INFO, message, null);
    }

    void error(final String message, final Throwable t) {
      log(Level.SEVERE, message, t);
    }

    void infoFormat(final String message, final Object... values) {
      logFormat(Level.INFO, message, values);
    }

    /// A second wrapper level: resolveCaller must drop this frame too, not just skip a
    /// fixed count.
    void infoNested(final String message) {
      info(message);
    }
  }

  private record Captured(TestLogger logger, List<LogRecord> records) {
  }

  private static Captured captured(final Level level) {
    final var logger = new TestLogger();
    logger.jul.setUseParentHandlers(false);
    logger.jul.setLevel(level);
    final var records = new ArrayList<LogRecord>();
    logger.jul.addHandler(new Handler() {
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
    return new Captured(logger, records);
  }

  @Test
  void logResolvesTheCallerOutsideTheLoggerClass() {
    final var captured = captured(Level.ALL);
    captured.logger.info("hello");

    assertEquals(1, captured.records.size());
    final var record = captured.records.getFirst();
    assertEquals("hello", record.getMessage());
    assertEquals(getClass().getName(), record.getSourceClassName(),
        "the walker must skip the logger's own frames");
    assertEquals("logResolvesTheCallerOutsideTheLoggerClass", record.getSourceMethodName());
    assertNull(record.getThrown());
  }

  @Test
  void deeplyNestedWrappersStillResolveTheOutsideCaller() {
    final var captured = captured(Level.ALL);
    captured.logger.infoNested("nested");

    assertEquals(1, captured.records.size());
    final var record = captured.records.getFirst();
    assertEquals(getClass().getName(), record.getSourceClassName(),
        "every logger-class frame must be dropped, not a fixed count");
    assertEquals("deeplyNestedWrappersStillResolveTheOutsideCaller", record.getSourceMethodName());
  }

  @Test
  void logFormatWithNullValuesArrayEmitsTheRawMessage() {
    final var captured = captured(Level.ALL);
    captured.logger.infoFormat("raw {}", (Object[]) null);
    assertEquals("raw {}", captured.records.getFirst().getMessage());
  }

  @Test
  void logCarriesTheThrowable() {
    final var captured = captured(Level.ALL);
    final var failure = new IllegalStateException("boom");
    captured.logger.error("failed", failure);

    assertEquals(1, captured.records.size());
    assertSame(failure, captured.records.getFirst().getThrown());
    assertEquals("failed", captured.records.getFirst().getMessage());
  }

  @Test
  void disabledLevelEmitsNothing() {
    final var captured = captured(Level.OFF);
    captured.logger.info("dropped");
    captured.logger.infoFormat("dropped {}", 1);
    assertTrue(captured.records.isEmpty());
  }

  @Test
  void logFormatSubstitutesBeforeEmitting() {
    final var captured = captured(Level.ALL);
    captured.logger.infoFormat("sum={} of {}", 3, new int[]{1, 2});
    assertEquals("sum=3 of [1, 2]", captured.records.getFirst().getMessage());
  }

  @Test
  void logFormatWithoutValuesEmitsTheRawMessage() {
    final var captured = captured(Level.ALL);
    captured.logger.infoFormat("raw {}");
    assertEquals("raw {}", captured.records.getFirst().getMessage());
  }

  @Test
  void logFormatWithNullMessageEmitsNull() {
    final var captured = captured(Level.ALL);
    captured.logger.infoFormat(null, 1);
    assertEquals(1, captured.records.size());
    assertNull(captured.records.getFirst().getMessage());
  }
}
