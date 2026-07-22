package software.sava.http_servers.core.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.http_servers.core.handlers.HandlerUtil.*;

final class HandlerUtilTests {

  @Test
  void indexOfParamMatchesAtQueryStart() {
    assertEquals(0, indexOfParam("page=2", "page="));
    assertEquals(0, indexOfParam("page=2&size=10", "page="));
  }

  @Test
  void indexOfParamMatchesAfterSeparator() {
    assertEquals(8, indexOfParam("size=10&page=2", "page="));
  }

  @Test
  void indexOfParamRejectsSubstringMatches() {
    assertEquals(-1, indexOfParam("perpage=5", "page="));
    // skips the embedded match, then finds the real one
    assertEquals(10, indexOfParam("perpage=5&page=2", "page="));
  }

  @Test
  void indexOfParamMissing() {
    assertEquals(-1, indexOfParam("size=10", "page="));
    assertEquals(-1, indexOfParam("", "page="));
  }

  @Test
  void parseStringParam() {
    assertEquals("2", parseParam("page=2", "page="));
    assertEquals("2", parseParam("page=2&size=10", "page="));
    assertEquals("2", parseParam("size=10&page=2", "page="));
    assertEquals("2", parseParam("a=1&page=2&z=3", "page="));
  }

  @Test
  void parseStringParamDefaults() {
    assertNull(parseParam("size=10", "page="));
    assertNull(parseParam((String) null, "page="));
    assertEquals("1", parseParam("size=10", "page=", "1"));
    assertEquals("1", parseParam(null, "page=", "1"));
    assertEquals("2", parseParam("page=2", "page=", "1"));
  }

  @Test
  void parseStringParamEmptyValue() {
    assertEquals("", parseParam("page=", "page="));
    assertEquals("", parseParam("page=&size=10", "page="));
    assertEquals("", parseParam("size=10&page=", "page="));
  }

  @Test
  void parseStringParamNotMatchedInsideOtherValue() {
    assertNull(parseParam("q=page%3D7", "page="));
  }

  @Test
  void parseBooleanParam() {
    assertTrue(parseParam("verbose=true", "verbose=", false));
    assertFalse(parseParam("verbose=false", "verbose=", true));
    assertTrue(parseParam("size=10", "verbose=", true));
    assertTrue(parseParam(null, "verbose=", true));
    assertFalse(parseParam(null, "verbose=", false));
    assertFalse(parseBoolParam("size=10", "verbose="));
    assertFalse(parseBoolParam(null, "verbose="));
    assertTrue(parseBoolParam("a=1&verbose=true&z=3", "verbose="));
    // substring of another parameter name must not match
    assertFalse(parseBoolParam("noverbose=true", "verbose="));
  }

  @Test
  void parseIntParamValues() {
    assertEquals(7, parseParam("page=7", "page=", 1));
    assertEquals(-7, parseParam("page=-7", "page=", 1));
    assertEquals(7, parseParam("a=1&page=7&z=3", "page=", 1));
    assertEquals(1, parseParam("size=10", "page=", 1));
    assertEquals(1, parseParam(null, "page=", 1));
    assertEquals(0, parseIntParam("size=10", "page="));
    assertEquals(7, parseIntParam("page=7", "page="));
    assertEquals(3, parseIntParam("perpage=5&page=3", "page="));
  }

  @Test
  void parseIntParamMalformedThrows() {
    assertThrows(NumberFormatException.class, () -> parseIntParam("page=seven", "page="));
  }

  @Test
  void parseIntParamsValues() {
    assertArrayEquals(new int[]{5}, parseIntParams("ids=5", "ids=", 4));
    assertArrayEquals(new int[]{1, 2, 3}, parseIntParams("ids=1,2,3", "ids=", 4));
    assertArrayEquals(new int[]{1, -2}, parseIntParams("ids=1,-2", "ids=", 4));
    assertArrayEquals(new int[]{1, 2}, parseIntParams("ids=1,2&x=3", "ids=", 4));
    assertArrayEquals(new int[]{1, 2}, parseIntParams("x=3&ids=1,2", "ids=", 4));
  }

  @Test
  void parseIntParamsMissingOrBlank() {
    assertNull(parseIntParams("x=3", "ids=", 4));
    assertNull(parseIntParams(null, "ids=", 4));
    // the boundary-rejected match leaves residual text past the param length, which the
    // absent-param guard must discard rather than parse
    assertNull(parseIntParams("monkeys=1", "ids=", 4), "absent parameter");
    assertNull(parseIntParams("ids=", "ids=", 4));
    assertNull(parseIntParams("ids=&x=3", "ids=", 4));
  }

  @Test
  void parseIntParamsMalformedThrows() {
    assertThrows(NumberFormatException.class, () -> parseIntParams("ids=1,,2", "ids=", 4));
    // a leading comma splits off an empty first element; the empty string is what must fail
    final var ex = assertThrows(NumberFormatException.class, () -> parseIntParams("ids=,1", "ids=", 2));
    assertTrue(ex.getMessage().contains("\"\""));
  }

  @Test
  void parseParamAtIndex() {
    final var query = "size=10&page=2";
    assertEquals("2", parseParam(query, 8, "page="));
    assertEquals("10", parseParam(query, 0, "size="));
  }

  @Test
  void valuesArePercentDecoded() {
    assertEquals("a&b", parseParam("v=a%26b", "v="));
    assertEquals("x=y", parseParam("v=x%3Dy", "v="));
    assertEquals("hello world", parseParam("v=hello%20world", "v="));
    assertEquals("hello world", parseParam("v=hello+world", "v="), "'+' decodes to a space");
    assertEquals("caf\u00e9", parseParam("v=caf%C3%A9", "v="), "multi-byte UTF-8 escapes");
  }

  @Test
  void encodedSeparatorsDoNotSplitStructure() {
    // %26 inside a value must not act as a parameter separator...
    assertEquals("a&admin=true", parseParam("owner=a%26admin%3Dtrue", "owner="));
    // ...so the injected parameter is not visible
    assertFalse(parseBoolParam("owner=a%26admin%3Dtrue", "admin="));
    // and %2C inside a list element must not act as an element separator
    final var ex = assertThrows(NumberFormatException.class, () -> parseIntParams("ids=1%2C2", "ids=", 2));
    assertTrue(ex.getMessage().contains("1,2"), ex.getMessage());
  }

  @Test
  void listElementsAreDecodedIndividually() {
    assertArrayEquals(new int[]{1, 22, 3}, parseIntParams("ids=%31,2%32,3", "ids=", 4));
  }

  @Test
  void decodedParamsFeedTheTypedParsers() {
    assertTrue(parseBoolParam("flag=%74rue", "flag="));
    assertEquals(42, parseIntParam("n=%34%32", "n="));
  }

  @Test
  void malformedEscapeThrows() {
    assertThrows(IllegalArgumentException.class, () -> parseParam("v=%zz", "v="));
    assertThrows(IllegalArgumentException.class, () -> parseParam("v=abc%2", "v="));
  }
}
