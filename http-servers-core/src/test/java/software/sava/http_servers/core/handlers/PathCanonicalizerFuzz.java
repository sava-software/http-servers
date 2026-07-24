package software.sava.http_servers.core.handlers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/// Jazzer entry point for the routing path canonicalizer.
///
/// A naive reference would be the same scanner written twice, so the oracle is generative:
/// the input bytes choose path tokens whose documented meaning is known, the expected
/// canonical form (or expected refusal) is constructed alongside, and the canonicalizer
/// must reproduce it exactly. Every token starts with `'/'` and is internally complete —
/// no token can change the meaning of its neighbor.
///
/// Two modes, selected by the first input byte:
/// - **generative** (even): remaining bytes select tokens; expected output built alongside.
/// - **arbitrary** (odd): remaining bytes are the raw path; the canonicalizer must never
///   throw, and an accepted result must be rooted and hold no dot, empty, backslash or NUL
///   segment — the properties routing depends on.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :http-servers-core:fuzzPathCanonicalizer [-PmaxFuzzTime=<seconds>]`.
public final class PathCanonicalizerFuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length == 0) {
      return;
    }
    if ((data[0] & 1) == 0) {
      generative(data);
    } else {
      arbitrary(new String(data, 1, data.length - 1, StandardCharsets.UTF_8));
    }
  }

  private static final String[] PLAIN = {"a", "bb", "files", "x1"};
  private static final String[][] ENCODED = {{"%61", "a"}, {"%41", "A"}, {"%C3%A9", "é"}, {"%7E", "~"}};
  private static final String[] REJECT = {"%2F", "%5c", "%00", "%25", "%2561", "%2e%2e", "%2e", ".%2e", "%G1", "%4", "%", "\\", "\0", "/"};

  static void generative(final byte[] data) {
    final var path = new StringBuilder("/");
    final var expectedSegments = new ArrayList<String>();
    boolean expectReject = false;
    boolean trailing = true;
    boolean first = true;
    for (int i = 1; i < data.length; ++i) {
      final int choice = Byte.toUnsignedInt(data[i]);
      if (!first) {
        path.append('/');
      }
      first = false;
      switch (choice % 5) {
        case 0 -> {
          final var segment = PLAIN[choice % PLAIN.length];
          path.append(segment);
          expectedSegments.add(segment);
          trailing = false;
        }
        case 1 -> {
          final var encoded = ENCODED[choice % ENCODED.length];
          path.append(encoded[0]);
          expectedSegments.add(encoded[1]);
          trailing = false;
        }
        case 2 -> {
          path.append('.');
          trailing = true;
        }
        case 3 -> {
          path.append("..");
          if (expectedSegments.isEmpty()) {
            expectReject = true;
          } else {
            expectedSegments.removeLast();
          }
          trailing = true;
        }
        default -> {
          // a reject token poisons the whole path regardless of what follows
          path.append(REJECT[choice % REJECT.length]).append("/e");
          expectReject = true;
          trailing = false;
        }
      }
    }
    final String expected;
    if (expectReject) {
      expected = null;
    } else if (expectedSegments.isEmpty()) {
      expected = "/";
    } else {
      final var joined = new StringBuilder();
      for (final var segment : expectedSegments) {
        joined.append('/').append(segment);
      }
      if (trailing) {
        joined.append('/');
      }
      expected = joined.toString();
    }
    final var actual = PathCanonicalizer.canonicalize(path.toString());
    if (!java.util.Objects.equals(expected, actual)) {
      throw new AssertionError(
          "canonicalize(" + path + "): expected " + expected + " but was " + actual);
    }
  }

  static void arbitrary(final String path) {
    final String canonical;
    try {
      canonical = PathCanonicalizer.canonicalize(path);
    } catch (final RuntimeException e) {
      throw new AssertionError("canonicalize must never throw: " + path, e);
    }
    if (canonical == null || canonical.equals("/")) {
      return;
    }
    if (canonical.charAt(0) != '/') {
      throw new AssertionError("accepted paths are rooted: " + path + " -> " + canonical);
    }
    if (canonical.indexOf('\\') >= 0 || canonical.indexOf('\0') >= 0 || canonical.indexOf('%') >= 0) {
      throw new AssertionError("separator smuggled through: " + path + " -> " + canonical);
    }
    final var segments = canonical.substring(1).split("/", -1);
    for (int i = 0; i < segments.length; ++i) {
      final var segment = segments[i];
      final boolean trailingEmpty = i == segments.length - 1 && segment.isEmpty() && canonical.length() > 1;
      if (!trailingEmpty && (segment.isEmpty() || segment.equals(".") || segment.equals(".."))) {
        throw new AssertionError("uncanonical segment survived: " + path + " -> " + canonical);
      }
    }
  }

  private PathCanonicalizerFuzz() {
  }
}
