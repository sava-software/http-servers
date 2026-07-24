package software.sava.http_servers.core.handlers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/// Canonicalizes a raw request-target path for routing.
///
/// Handlers are registered under literal, decoded paths, but a request target may spell the
/// same path many ways — percent-encoded letters, dot segments, empty segments. Routing on
/// the raw form makes the match backend-dependent and lets an encoded traversal reach a
/// prefix-matched path handler, so every lookup first reduces the target to one canonical
/// form: percent-escapes are decoded per segment (UTF-8), {@code "."} segments are dropped
/// and {@code ".."} segments resolved against their parent, and a trailing slash is
/// preserved.
///
/// Ambiguous or malformed targets canonicalize to {@code null} and must be refused rather
/// than routed: a malformed escape, an escape or literal that would introduce a separator
/// or terminator into a segment ({@code %2F}, {@code %5C}, {@code %00}, a literal
/// backslash or NUL), a double-encoding ({@code %25}), a dot segment spelled with escapes
/// ({@code %2e%2e}), an empty
/// segment ({@code //}), a {@code ".."} that would escape the root, or a target that does
/// not start with {@code '/'}.
///
/// The canonical form decides **routing only**: the path a handler observes through
/// {@code Request.path()} stays as raw as the backend delivers it.
final class PathCanonicalizer {

  /// @return the canonical decoded path, or {@code null} if the target is ambiguous or
  /// malformed and the request must be refused.
  static String canonicalize(final String path) {
    if (path == null || path.isEmpty() || path.charAt(0) != '/') {
      return null;
    }
    final int len = path.length();
    final var segments = new ArrayList<String>();
    final var segment = new StringBuilder();
    byte[] escaped = null;
    int escapedLength = 0;
    boolean segmentHadEscape = false;
    boolean trailingSlash = false;
    for (int i = 1; i <= len; ++i) {
      final char c = i == len ? '/' : path.charAt(i);
      if (c == '%') {
        if (i + 2 >= len) {
          return null;
        }
        final int hi = Character.digit(path.charAt(i + 1), 16);
        final int lo = Character.digit(path.charAt(i + 2), 16);
        if (hi < 0 || lo < 0) {
          return null;
        }
        // '%' is refused so "%2541" cannot masquerade as "%41": double-encoding is an
        // ambiguity signal (Jetty's compliance layer refuses it too), and the canonical
        // path therefore never contains an escape introducer
        final byte decoded = (byte) ((hi << 4) | lo);
        if (decoded == '/' || decoded == '\\' || decoded == '%' || decoded == 0) {
          return null;
        }
        if (escaped == null) {
          escaped = new byte[len];
        }
        escaped[escapedLength++] = decoded;
        segmentHadEscape = true;
        i += 2;
        continue;
      }
      if (escapedLength > 0) {
        segment.append(new String(escaped, 0, escapedLength, StandardCharsets.UTF_8));
        escapedLength = 0;
      }
      if (c == '/') {
        final var name = segment.toString();
        segment.setLength(0);
        switch (name) {
          case "" -> {
            if (i < len) {
              return null;
            }
            trailingSlash = true;
          }
          case ".", ".." -> {
            // an encoded dot segment ("%2e%2e") is an ambiguity signal, never resolved
            if (segmentHadEscape) {
              return null;
            }
            if (name.length() == 2) {
              if (segments.isEmpty()) {
                return null;
              }
              segments.removeLast();
            }
            trailingSlash = i == len;
          }
          default -> segments.add(name);
        }
        segmentHadEscape = false;
      } else if (c == '\\' || c == 0) {
        return null;
      } else {
        segment.append(c);
      }
    }
    // when no segment remains, the last token was "", "." or ".." — each leaves the
    // trailing flag set, so the builder below yields "/" on its own
    final var canonical = new StringBuilder(path.length());
    for (final var name : segments) {
      canonical.append('/').append(name);
    }
    if (trailingSlash) {
      canonical.append('/');
    }
    return canonical.toString();
  }

  private PathCanonicalizer() {
  }
}
