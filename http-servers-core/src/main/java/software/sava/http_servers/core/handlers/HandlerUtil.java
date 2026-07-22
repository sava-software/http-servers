package software.sava.http_servers.core.handlers;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/// Query string parameter parsing helpers.
///
/// The {@code param} argument is expected to include the trailing {@code '='}, e.g.
/// {@code parseParam(query, "page=")}. A parameter only matches at the start of the query or
/// immediately after a {@code '&'} separator, so {@code "page="} does not match inside
/// {@code "perpage="}.
///
/// Structure is split on the **raw** query string — separators are literal {@code '&'} (and
/// {@code ','} for list values), so percent-encoded delimiters inside a value can never be
/// mistaken for separators — and each extracted value is then percent-decoded
/// (`application/x-www-form-urlencoded`: {@code %XX} escapes plus {@code '+'} as space).
/// A malformed escape sequence throws {@link IllegalArgumentException}.
public class HandlerUtil {

  /// @return the index of {@code param} at a parameter boundary (start of query or after
  /// {@code '&'}), otherwise {@code -1}.
  public static int indexOfParam(final String query, final String param) {
    for (int index = query.indexOf(param); index >= 0; index = query.indexOf(param, index + 1)) {
      if (index == 0 || query.charAt(index - 1) == '&') {
        return index;
      }
    }
    return -1;
  }

  /// The raw, still-encoded value: from the end of {@code param} to the next {@code '&'} or
  /// the end of the query.
  static String parseRawParam(final String query, final int index, final String param) {
    final int from = index + param.length();
    final int to = query.indexOf('&', from);
    return to < 0
        ? query.substring(from)
        : query.substring(from, to);
  }

  public static String parseParam(final String query, final int index, final String param) {
    return URLDecoder.decode(parseRawParam(query, index, param), StandardCharsets.UTF_8);
  }

  public static boolean parseParam(final String query, final String param, final boolean defaultValue) {
    if (query == null) {
      return defaultValue;
    } else {
      final int index = indexOfParam(query, param);
      return index < 0 ? defaultValue : Boolean.parseBoolean(parseParam(query, index, param));
    }
  }

  public static boolean parseBoolParam(final String query, final String param) {
    return parseParam(query, param, false);
  }

  public static int parseParam(final String query, final String param, final int defaultValue) {
    if (query == null) {
      return defaultValue;
    } else {
      final int index = indexOfParam(query, param);
      return index < 0 ? defaultValue : Integer.parseInt(parseParam(query, index, param));
    }
  }

  public static int parseIntParam(final String query, final String param) {
    return parseParam(query, param, 0);
  }

  public static String parseParam(final String query, final String param, final String defaultValue) {
    if (query == null) {
      return defaultValue;
    } else {
      final int index = indexOfParam(query, param);
      return index < 0 ? defaultValue : parseParam(query, index, param);
    }
  }

  public static String parseParam(final String query, final String param) {
    return parseParam(query, param, null);
  }

  public static int[] parseIntParams(final String query, final String param, final int defaultSize) {
    if (query == null) {
      return null;
    }
    final int index = indexOfParam(query, param);
    if (index < 0) {
      return null;
    }
    // split the raw value so an encoded comma cannot act as a separator, then decode each element
    final var val = parseRawParam(query, index, param);
    if (val.isBlank()) {
      return null;
    }
    final var values = new ArrayList<String>(defaultSize);
    for (int from = 0, to; ; ) {
      to = val.indexOf(',', from);
      if (to < 0) {
        values.add(val.substring(from));
        break;
      } else {
        values.add(val.substring(from, to));
        ++to;
        from = to;
      }
    }
    return values.stream()
        .map(value -> URLDecoder.decode(value, StandardCharsets.UTF_8))
        .mapToInt(Integer::parseInt)
        .toArray();
  }

  private HandlerUtil() {
  }
}
