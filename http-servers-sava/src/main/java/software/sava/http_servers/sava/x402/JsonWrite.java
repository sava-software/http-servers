package software.sava.http_servers.sava.x402;

/// Minimal JSON serialization helpers used by the x402 model records.
///
/// Parsing is handled with {@code systems.comodal.json_iterator}; for serialization the model is
/// small and fixed, so a tiny string builder based writer keeps the module dependency-light.
final class JsonWrite {

  static void appendString(final StringBuilder b, final String key, final String value) {
    b.append('"').append(key).append("\":");
    appendValue(b, value);
  }

  static void appendValue(final StringBuilder b, final String value) {
    if (value == null) {
      b.append("null");
      return;
    }
    b.append('"');
    for (int i = 0, len = value.length(); i < len; ++i) {
      final char c = value.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        case '\b' -> b.append("\\b");
        case '\f' -> b.append("\\f");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    b.append('"');
  }

  private JsonWrite() {
  }
}
