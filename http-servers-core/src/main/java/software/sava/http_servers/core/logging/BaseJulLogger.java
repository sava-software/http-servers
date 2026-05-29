package software.sava.http_servers.core.logging;

import java.util.Arrays;
import java.util.logging.Level;

public abstract class BaseJulLogger {

  private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

  protected final java.util.logging.Logger jul;
  protected final String className;

  protected BaseJulLogger(final Class<?> klass) {
    this.jul = java.util.logging.Logger.getLogger(klass.getName());
    this.className = getClass().getName();
  }

  private record Caller(String sourceClass, String sourceMethod) {
  }

  protected void log(final Level level, final String message, final Throwable t) {
    if (!jul.isLoggable(level)) {
      return;
    }
    final var caller = resolveCaller();
    if (t == null) {
      jul.logp(level, caller.sourceClass, caller.sourceMethod, message);
    } else {
      jul.logp(level, caller.sourceClass, caller.sourceMethod, message, t);
    }
  }

  protected void logFormat(final Level level, final String message, final Object[] values) {
    if (!jul.isLoggable(level)) {
      return;
    }
    final var caller = resolveCaller();
    if (message == null || values == null || values.length == 0) {
      jul.logp(level, caller.sourceClass, caller.sourceMethod, message);
    } else {
      final var formatted = formatPlaceholders(message, values);
      jul.logp(level, caller.sourceClass, caller.sourceMethod, formatted);
    }
  }

  private Caller resolveCaller() {
    final var stackFrame = WALKER.walk(
        stream -> stream.skip(3).dropWhile(f -> f.getClassName().startsWith(className)
        ).findFirst().orElse(null)
    );
    return stackFrame == null
        ? new Caller(jul.getName(), "log")
        : new Caller(stackFrame.getClassName(), stackFrame.getMethodName());
  }

  private static String formatPlaceholders(final String message, final Object... values) {
    if (message.indexOf('{') < 0) {
      return message;
    }
    final int len = message.length();
    final var sb = new StringBuilder(len << 2);
    for (int i = 0, next = 0; i < len; i++) {
      char c = message.charAt(i);
      if (c == '\\') {
        // Support simple escaping of "\{}" -> "{}" without substitution
        if (i + 2 <= len && i + 1 < len && message.charAt(i + 1) == '{') {
          // Skip the backslash, emit '{'
          sb.append('{');
          i++; // skip '{'
        } else {
          sb.append(c);
        }
      } else if (c == '{' && i + 1 < len && message.charAt(i + 1) == '}') {
        if (next < values.length) {
          sb.append(stringify(values[next++]));
        } else {
          // Not enough values; leave placeholder intact
          sb.append("{}");
        }
        i++; // skip '}'
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String stringify(final Object v) {
    if (v == null) {
      return "null";
    }
    final var cls = v.getClass();
    if (!cls.isArray()) {
      return v.toString();
    }
    return switch (v) {
      case Object[] oa -> Arrays.deepToString(oa);
      case int[] a -> Arrays.toString(a);
      case long[] a -> Arrays.toString(a);
      case double[] a -> Arrays.toString(a);
      case float[] a -> Arrays.toString(a);
      case boolean[] a -> Arrays.toString(a);
      case byte[] a -> Arrays.toString(a);
      case short[] a -> Arrays.toString(a);
      case char[] a -> Arrays.toString(a);
      default -> v.toString();
    };
  }
}
