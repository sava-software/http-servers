package software.sava.http_servers.soak;

import java.util.Arrays;

/// Deterministic content shared by the server's `/big` route and the load generator's
/// verification. Byte `i` of a response is [#pattern(int)] of `i` whatever length was asked
/// for, so a client checks any body against a prefix of one shared master array instead of
/// regenerating it per request.
final class SoakBytes {

  /// The largest `/big` response, 1 MiB.
  static final int MAX_BIG = 1 << 20;

  private static final byte[] MASTER = new byte[MAX_BIG];

  static {
    for (int i = 0; i < MAX_BIG; ++i) {
      MASTER[i] = pattern(i);
    }
  }

  static byte pattern(final int i) {
    return (byte) (i * 31 + (i >>> 8) + 7);
  }

  /// The first `n` bytes of the pattern, `0 <= n <= MAX_BIG`, as a fresh array.
  static byte[] big(final int n) {
    return Arrays.copyOf(MASTER, n);
  }

  /// The index of the first byte of `body` that departs from the pattern, or -1 when the
  /// whole body (at most [#MAX_BIG] bytes) matches.
  static int firstMismatch(final byte[] body) {
    final int n = Math.min(body.length, MAX_BIG);
    for (int i = 0; i < n; ++i) {
      if (body[i] != MASTER[i]) {
        return i;
      }
    }
    return body.length > MAX_BIG ? MAX_BIG : -1;
  }

  private SoakBytes() {
  }
}
