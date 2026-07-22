package software.sava.http_servers.core.logging;

import java.nio.charset.StandardCharsets;

/// Jazzer entry point for the placeholder formatter.
///
/// Two modes, selected by the second input byte:
///
/// - **arbitrary**: the raw bytes become the message; the formatter must never throw, never
///   return null, and must return a message without braces unchanged.
/// - **generated**: the message is assembled token by token — literals, `{}`, the `\{}`
///   escape, `\c`, an unclosed `{c`, a lone `}` — while the expected output is constructed
///   alongside from the token semantics. Every token ends on a safe character or `}`, so no
///   token can change the meaning of the next; the expected string is ground truth by
///   construction, not a re-implementation of the scanner. Value stringification reuses
///   [BaseJulLogger#stringify], which is pinned separately by unit tests — this harness
///   targets the scanning and substitution logic around it.
///
/// The first byte sizes the substitution values from a fixed pool (including nulls, arrays
/// and brace-bearing strings, which must never be re-scanned).
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :http-servers-core:fuzzFormatPlaceholders [-PmaxFuzzTime=<seconds>]`.
public final class FormatPlaceholdersFuzz {

  private static final Object[] POOL = {
      null, 42, "text", "{}", "\\{", new int[]{1, 2}, new Object[]{"a", new long[]{3}}, 4.5d
  };

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 2) {
      return;
    }
    final int valueCount = Byte.toUnsignedInt(data[0]) % (POOL.length + 1);
    final var values = new Object[valueCount];
    for (int i = 0; i < valueCount; i++) {
      values[i] = POOL[(Byte.toUnsignedInt(data[0]) + i) % POOL.length];
    }
    if ((data[1] & 1) == 0) {
      arbitrary(data, values);
    } else {
      generated(data, values);
    }
  }

  private static void arbitrary(final byte[] data, final Object[] values) {
    final var message = new String(data, 2, data.length - 2, StandardCharsets.UTF_8);
    final var out = BaseJulLogger.formatPlaceholders(message, values);
    if (out == null) {
      throw new AssertionError("formatPlaceholders returned null for: " + message);
    }
    if (message.indexOf('{') < 0 && !out.equals(message)) {
      throw new AssertionError("brace-free message was altered: '" + message + "' -> '" + out + "'");
    }
  }

  private static void generated(final byte[] data, final Object[] values) {
    final var message = new StringBuilder();
    final var expected = new StringBuilder();
    int next = 0;
    for (int i = 2; i < data.length; i++) {
      final int b = Byte.toUnsignedInt(data[i]);
      final char safe = (char) ('a' + ((b / 6) % 26));
      switch (b % 6) {
        case 0 -> {
          message.append(safe);
          expected.append(safe);
        }
        case 1 -> {
          message.append("{}");
          if (next < values.length) {
            expected.append(BaseJulLogger.stringify(values[next++]));
          } else {
            expected.append("{}");
          }
        }
        case 2 -> {
          message.append("\\{}");
          expected.append("{}");
        }
        case 3 -> {
          if (i == data.length - 1) {
            // a trailing lone backslash is emitted as-is
            message.append('\\');
            expected.append('\\');
          } else {
            message.append('\\').append(safe);
            expected.append('\\').append(safe);
          }
        }
        case 4 -> {
          // an unclosed brace is a literal
          message.append('{').append(safe);
          expected.append('{').append(safe);
        }
        case 5 -> {
          message.append('}');
          expected.append('}');
        }
      }
    }
    final var out = BaseJulLogger.formatPlaceholders(message.toString(), values);
    if (!out.contentEquals(expected)) {
      throw new AssertionError("substitution disagrees with the generated expectation for message '"
          + message + "' with " + values.length + " values: expected '" + expected + "' got '" + out + "'");
    }
  }

  private FormatPlaceholdersFuzz() {
  }
}
