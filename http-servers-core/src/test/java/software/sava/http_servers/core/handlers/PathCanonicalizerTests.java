package software.sava.http_servers.core.handlers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PathCanonicalizerTests {

  @ParameterizedTest
  @CsvSource({
      "/,                    /",
      "/hello,               /hello",
      "/hello/,              /hello/",
      "/files/x,             /files/x",
      "/a/b/c,               /a/b/c",
      "/a/./b,               /a/b",
      "/a/../b,              /b",
      "/a/b/..,              /a/",
      "/a/.,                 /a/",
      "/a/..,                /",
      "/a/./,                /a/",
      "/a/../,               /",
      "/a/b/../../c,         /c",
      "/%66iles/x,           /files/x",
      "/a%41,                /aA",
      "/caf%C3%A9,           /café",
      "/a+b,                 /a+b",
      "/a%20b,               /a b",
  })
  void canonicalForms(final String raw, final String expected) {
    assertEquals(expected, PathCanonicalizer.canonicalize(raw), raw);
  }

  /// A `0` hex digit is a valid escape digit, in both positions — `hi < 0` and `lo < 0`
  /// must stay strict comparisons.
  @Test
  void zeroHexDigitsDecode() {
    assertEquals("/a\tb", PathCanonicalizer.canonicalize("/a%09b"));
    assertEquals("/a@b", PathCanonicalizer.canonicalize("/a%40b"));
  }

  /// `%2561` is refused outright, never double-decoded to `a` — double-encoding is how
  /// traversals sneak past single-pass filters, so `%25` joins the refused escapes.
  @Test
  void doubleEncodingIsRefused() {
    assertNull(PathCanonicalizer.canonicalize("/%2561"));
    assertNull(PathCanonicalizer.canonicalize("/a%25b"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // escapes that would introduce a separator or terminator
      "/files%2Fx", "/files%2f..%2fhello", "/a%5Cb", "/a%00b",
      // encoded dot segments are ambiguity signals, never resolved
      "/files/%2e%2e/hello", "/files/%2E%2E/hello", "/files/.%2e/hello", "/files/%2e/hello",
      // malformed escapes
      "/a%", "/a%4", "/a%GG", "/a%g1", "/a%1g",
      // empty segments
      "//hello", "/a//b",
      // escaping the root
      "/..", "/../a", "/a/../..",
      // literal separators and terminators
      "/a\\b", "/a\u0000b",
      // not a rooted path
      "", "a", "*", "hello/there",
  })
  void ambiguousOrMalformedIsRefused(final String raw) {
    assertNull(PathCanonicalizer.canonicalize(raw), raw);
  }

  @Test
  void nullIsRefused() {
    assertNull(PathCanonicalizer.canonicalize(null));
  }

  /// The escape buffer flushes on the segment boundary: escapes at the very end of the
  /// target still land in the final segment.
  @Test
  void trailingEscapeIsDecoded() {
    assertEquals("/aA", PathCanonicalizer.canonicalize("/a%41"));
    assertEquals("/aA/", PathCanonicalizer.canonicalize("/a%41/"));
  }

  /// Consecutive escapes decode as one UTF-8 run, not byte-by-byte chars.
  @Test
  void multiByteUtf8DecodesAsARun() {
    assertEquals("/☃", PathCanonicalizer.canonicalize("/%E2%98%83"));
  }
}
