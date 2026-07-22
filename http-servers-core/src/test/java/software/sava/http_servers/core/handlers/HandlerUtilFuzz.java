package software.sava.http_servers.core.handlers;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Jazzer entry point for the query-string parsers.
///
/// `HandlerUtil` is a hand-rolled boundary scanner; this harness re-implements the same
/// contract naively — split on literal `&`, prefix-match the parameter, decode last — and
/// requires the two to agree on every input: same value, same absence, same integers, or
/// the same exception class. Crash-only fuzzing cannot see a wrong answer, so agreement is
/// the property, with the documented throw set (`IllegalArgumentException`, including
/// `NumberFormatException`) tolerated only when both implementations throw it.
///
/// The first input byte selects the parameter under test; the rest is the query string.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :http-servers-core:fuzzHandlerUtil [-PmaxFuzzTime=<seconds>]`.
public final class HandlerUtilFuzz {

  static final String[] PARAMS = {"a=", "page=", "ids=", "flag=", "search="};

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length == 0) {
      return;
    }
    final var param = PARAMS[Byte.toUnsignedInt(data[0]) % PARAMS.length];
    final var query = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);

    // parseParam: same value / absence / exception class as the reference
    final Outcome<String> value = Outcome.of(() -> HandlerUtil.parseParam(query, param));
    final Outcome<String> refValue = Outcome.of(() -> referenceParse(query, param));
    value.assertAgrees(refValue, "parseParam", query, param);

    // the boolean and int views must be derived from that same value
    final Outcome<Boolean> bool = Outcome.of(() -> HandlerUtil.parseBoolParam(query, param));
    final Outcome<Boolean> refBool = refValue.map(v -> v != null && Boolean.parseBoolean(v));
    bool.assertAgrees(refBool, "parseBoolParam", query, param);

    final Outcome<Integer> intValue = Outcome.of(() -> HandlerUtil.parseIntParam(query, param));
    final Outcome<Integer> refInt = refValue.map(v -> v == null ? 0 : Integer.parseInt(v));
    intValue.assertAgrees(refInt, "parseIntParam", query, param);

    // parseIntParams: raw comma split, then per-element decode
    final Outcome<String> ints = Outcome.of(() -> Arrays.toString(HandlerUtil.parseIntParams(query, param, 4)));
    final Outcome<String> refInts = Outcome.of(() -> Arrays.toString(referenceInts(query, param)));
    ints.assertAgrees(refInts, "parseIntParams", query, param);
  }

  /// Naive reference: split on literal `&`, prefix-match, decode the remainder.
  static String referenceParse(final String query, final String param) {
    for (final var pair : query.split("&", -1)) {
      if (pair.startsWith(param)) {
        return URLDecoder.decode(pair.substring(param.length()), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  static int[] referenceInts(final String query, final String param) {
    String raw = null;
    for (final var pair : query.split("&", -1)) {
      if (pair.startsWith(param)) {
        raw = pair.substring(param.length());
        break;
      }
    }
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return Arrays.stream(raw.split(",", -1))
        .map(element -> URLDecoder.decode(element, StandardCharsets.UTF_8))
        .mapToInt(Integer::parseInt)
        .toArray();
  }

  private record Outcome<T>(T value, Class<? extends RuntimeException> thrown) {

    interface Call<T> {
      T get();
    }

    static <T> Outcome<T> of(final Call<T> call) {
      try {
        return new Outcome<>(call.get(), null);
      } catch (final IllegalArgumentException expected) {
        // includes NumberFormatException; anything else is a finding and propagates
        return new Outcome<>(null, expected.getClass());
      }
    }

    <R> Outcome<R> map(final java.util.function.Function<T, R> mapper) {
      if (thrown != null) {
        return new Outcome<>(null, thrown);
      }
      try {
        return new Outcome<>(mapper.apply(value), null);
      } catch (final IllegalArgumentException expected) {
        return new Outcome<>(null, expected.getClass());
      }
    }

    void assertAgrees(final Outcome<T> reference, final String method, final String query, final String param) {
      if (!java.util.Objects.equals(value, reference.value) || thrown != reference.thrown) {
        throw new AssertionError(method + " disagrees with the reference for param '" + param
            + "' query '" + query + "': impl=" + this + " reference=" + reference);
      }
    }
  }

  private HandlerUtilFuzz() {
  }
}
