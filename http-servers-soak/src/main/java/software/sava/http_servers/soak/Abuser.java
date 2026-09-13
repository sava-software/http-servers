package software.sava.http_servers.soak;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/// Profile (c): a raw-socket abuser cycling through the hostile shapes README.md documents
/// per backend, with the expectations taken from [Backend]: an oversized `Content-Length`
/// past the cap with only a small prefix sent, an unsupported `Expect`, an `Expect:
/// 100-continue` announcing a body past the cap, a request line the codec cannot parse, an
/// HTTP/1.0 request, a declared body abandoned mid-way (alternately FIN and RST) followed by
/// a probe that the server still answers, an absolute-form target, and an HTTP/1.1 request
/// with no `Host`. Cases a backend does not document are skipped and counted as such rather
/// than guessed; every case that runs is counted as run, so the load generator can tell a
/// case that never happened from one that passed. The silent-connection case lives on its
/// own worker, [IdleProber], because each check takes the whole documented timeout; it
/// records under the same `case:idle:*` keys. Abusers start at staggered points of the cycle.
final class Abuser implements Runnable {

  enum Case {
    OVERSIZED, EXPECT_UNSUPPORTED, EXPECT_CONTINUE_OVER_CAP, MALFORMED_LINE, HTTP10, ABORT_MID_BODY, ABSOLUTE_FORM, NO_HOST, IDLE;

    final String key;

    Case() {
      this.key = name().toLowerCase(Locale.ROOT);
    }
  }

  /// The cases the cycle runs; [Case#IDLE] is the [IdleProber]'s.
  static final Case[] CYCLE = {
      Case.OVERSIZED, Case.EXPECT_UNSUPPORTED, Case.EXPECT_CONTINUE_OVER_CAP, Case.MALFORMED_LINE,
      Case.HTTP10, Case.ABORT_MID_BODY, Case.ABSOLUTE_FORM, Case.NO_HOST
  };

  static final int OVERSIZED_PREFIX = 1024;
  static final int ABORT_DECLARED = 64 << 10;
  static final int ABORT_SENT = 4096;
  /// The pause between flushing an abandoned upload's prefix and resetting the socket, so the
  /// prefix has left the send buffer before an RST could discard it.
  static final int ABORT_PACE_MIN_MILLIS = 5;
  static final int ABORT_PACE_SPREAD_MILLIS = 15;

  private final int id;
  private final String host;
  private final int port;
  private final Backend backend;
  private final Stats stats;
  private final Run run;
  private final Random rnd;
  private int aborts;

  Abuser(final int id,
         final String host,
         final int port,
         final Backend backend,
         final Stats stats,
         final Run run,
         final long seed) {
    this.id = id;
    this.host = host;
    this.port = port;
    this.backend = backend;
    this.stats = stats;
    this.run = run;
    this.rnd = new Random(seed);
  }

  @Override
  public void run() {
    int next = id % CYCLE.length;
    while (run.running()) {
      final var abuse = CYCLE[next];
      next = (next + 1) % CYCLE.length;
      try {
        switch (abuse) {
          case OVERSIZED -> oversized();
          case EXPECT_UNSUPPORTED -> expectUnsupported();
          case EXPECT_CONTINUE_OVER_CAP -> expectContinueOverCap();
          case MALFORMED_LINE -> malformedLine();
          case HTTP10 -> http10();
          case ABORT_MID_BODY -> abortMidBody();
          case ABSOLUTE_FORM -> absoluteForm();
          case NO_HOST -> noHost();
          case IDLE -> throw new IllegalStateException("the idle case runs on the IdleProber");
        }
      } catch (final RawHttp.ClosedException e) {
        stats.mismatch(e.reset ? "reset-when-not-expected" : "close-when-not-expected", label(abuse) + ": " + e.getMessage());
      } catch (final SocketTimeoutException e) {
        stats.mismatch("timeout", label(abuse) + ": " + e);
      } catch (final IOException | RuntimeException e) {
        stats.exception(e, label(abuse));
      }
      try {
        run.think(rnd, 50, 150);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  static String label(final String worker, final Case abuse) {
    return worker + ' ' + abuse.key;
  }

  /// The case started, its skip conditions not applying.
  static void attempted(final Stats stats, final Case abuse) {
    stats.count("case:" + abuse.key + ":run");
  }

  static void skipped(final Stats stats, final Case abuse, final String why) {
    stats.count("case:" + abuse.key + ":skipped (" + why + ')');
  }

  /// Everything the case asserts held.
  static void passed(final Stats stats, final Case abuse) {
    stats.count("case:" + abuse.key + ":ok");
    stats.casePassed();
  }

  /// The cases that were neither run nor skipped: a profile that never reached one of its
  /// shapes has not verified it, whatever else it counted.
  static List<String> unexercised(final Stats stats) {
    final var missing = new ArrayList<String>();
    for (final var abuse : Case.values()) {
      if (stats.counter("case:" + abuse.key + ":run") == 0
          && stats.counterSum(key -> key.startsWith("case:" + abuse.key + ":skipped")) == 0) {
        missing.add(abuse.key);
      }
    }
    return missing;
  }

  private String label(final Case abuse) {
    return label("abuser-" + id, abuse);
  }

  private String hostHeader() {
    return "Host: " + host + "\r\n";
  }

  private byte[] pingRequest() {
    return RawHttp.ascii("GET /ping HTTP/1.1\r\n" + hostHeader() + "\r\n");
  }

  private Socket connect() throws IOException {
    return RawHttp.connect(host, port, RawHttp.READ_TIMEOUT_MILLIS);
  }

  private static void write(final Socket socket, final byte[]... parts) throws IOException {
    final var out = socket.getOutputStream();
    for (final var part : parts) {
      out.write(part);
    }
    out.flush();
  }

  private boolean expectClosed(final InputStream in, final Case abuse, final String after) throws IOException {
    if (RawHttp.awaitClose(in)) {
      return true;
    }
    stats.mismatch("no-close-when-expected", label(abuse) + ": still open after " + after);
    return false;
  }

  /// The connection is proven kept by a `GET /ping` on it, verified like any other.
  private boolean expectKept(final Socket socket, final InputStream in, final Case abuse, final String after) throws IOException {
    write(socket, pingRequest());
    final RawHttp.Response ping;
    try {
      ping = RawHttp.readResponse(in);
    } catch (final RawHttp.ClosedException e) {
      stats.mismatch("close-when-not-expected", label(abuse) + ": connection not kept after " + after + ": " + e.getMessage());
      return false;
    }
    stats.request(ping.status());
    if (ping.status() == 200 && SoakServer.PING_JSON.equals(ping.bodyText())) {
      stats.ok();
      return true;
    }
    stats.mismatch("wrong-status", label(abuse) + ": GET /ping after " + after + " answered " + ping);
    return false;
  }

  /// The documented fate of the connection after a response: closed, kept, or not documented.
  private boolean afterwards(final Socket socket,
                             final InputStream in,
                             final Backend.Closes closes,
                             final Case abuse,
                             final String after) throws IOException {
    return switch (closes) {
      case YES -> expectClosed(in, abuse, after);
      case NO -> expectKept(socket, in, abuse, after);
      case EITHER -> true;
    };
  }

  /// A documented answer to a plain `GET /ping` shape: the status must be in the set and a
  /// 200 must carry the ping body.
  private boolean answered(final Case abuse, final Backend.Expectation expectation, final RawHttp.Response response) {
    stats.request(response.status());
    if (!expectation.accepts(response.status())) {
      stats.mismatch("wrong-status", label(abuse) + ": expected " + expectation.statuses() + " got " + response);
      return false;
    }
    if (response.status() == 200 && !SoakServer.PING_JSON.equals(response.bodyText())) {
      stats.mismatch("wrong-body", label(abuse) + ": " + response);
      return false;
    }
    stats.ok();
    return true;
  }

  /// A body declared one byte past the cap with only a small prefix sent: Netty answers 413
  /// with `Connection: close` off the head and closes without reading the body, and the
  /// README allows the client to see a reset before it has read the 413, so a reset with no
  /// status is accepted and counted separately.
  private void oversized() throws IOException {
    final long cap = backend.bodyCap();
    if (cap < 0) {
      skipped(stats, Case.OVERSIZED, "no documented body cap");
      return;
    }
    attempted(stats, Case.OVERSIZED);
    try (final var socket = connect()) {
      final var in = new BufferedInputStream(socket.getInputStream());
      final var prefix = new byte[OVERSIZED_PREFIX];
      rnd.nextBytes(prefix);
      final RawHttp.Response response;
      try {
        write(socket, RawHttp.ascii("POST /echo HTTP/1.1\r\n" + hostHeader()
            + "Content-Type: application/octet-stream\r\nContent-Length: " + (cap + 1) + "\r\n\r\n"), prefix);
        response = RawHttp.readResponse(in);
      } catch (final RawHttp.ClosedException e) {
        if (e.reset && e.bytesRead == 0) {
          stats.count("case:oversized:reset-before-413");
          passed(stats, Case.OVERSIZED);
          return;
        }
        throw e;
      } catch (final SocketException e) {
        if (RawHttp.isReset(e)) {
          stats.count("case:oversized:reset-before-413");
          passed(stats, Case.OVERSIZED);
          return;
        }
        throw e;
      }
      stats.request(response.status());
      if (response.status() != 413) {
        stats.mismatch("wrong-status", label(Case.OVERSIZED) + ": expected 413 got " + response);
      } else if (expectClosed(in, Case.OVERSIZED, "the 413")) {
        stats.ok();
        passed(stats, Case.OVERSIZED);
      }
    }
  }

  /// `Expect: foo`. Strict on Netty: head only, 417 framed `Content-Length: 0`. Lenient
  /// elsewhere: the body goes with the head and either documented answer is accepted. What
  /// happens to the connection afterwards is the table's `closes` column either way.
  private void expectUnsupported() throws IOException {
    final var expectation = backend.unsupportedExpect();
    attempted(stats, Case.EXPECT_UNSUPPORTED);
    try (final var socket = connect()) {
      final var in = new BufferedInputStream(socket.getInputStream());
      final var head = "POST /echo HTTP/1.1\r\n" + hostHeader() + "Content-Type: text/plain\r\nExpect: foo\r\nContent-Length: 5\r\n\r\n";
      final boolean strict = backend.strictExpect();
      write(socket, RawHttp.ascii(strict ? head : head + "hello"));
      final var response = RawHttp.readResponse(in);
      stats.request(response.status());
      if (!expectation.accepts(response.status())) {
        stats.mismatch("wrong-status", label(Case.EXPECT_UNSUPPORTED) + ": expected " + expectation.statuses() + " got " + response);
        return;
      }
      if (strict && !"0".equals(response.header("Content-Length"))) {
        stats.mismatch("wrong-framing", label(Case.EXPECT_UNSUPPORTED) + ": 417 without Content-Length: 0: " + response);
        return;
      }
      stats.ok();
      if (afterwards(socket, in, expectation.closes(), Case.EXPECT_UNSUPPORTED, "the " + response.status())) {
        passed(stats, Case.EXPECT_UNSUPPORTED);
      }
    }
  }

  /// `Expect: 100-continue` announcing a body past the cap: refused 413 with `Content-Length: 0`
  /// and `Connection: close` before any body is invited, then closed.
  private void expectContinueOverCap() throws IOException {
    final long cap = backend.bodyCap();
    if (cap < 0) {
      skipped(stats, Case.EXPECT_CONTINUE_OVER_CAP, "no documented body cap");
      return;
    }
    attempted(stats, Case.EXPECT_CONTINUE_OVER_CAP);
    try (final var socket = connect()) {
      final var in = new BufferedInputStream(socket.getInputStream());
      write(socket, RawHttp.ascii("POST /echo HTTP/1.1\r\n" + hostHeader()
          + "Content-Type: application/octet-stream\r\nExpect: 100-continue\r\nContent-Length: " + (cap + 1) + "\r\n\r\n"));
      final var response = RawHttp.readResponse(in);
      stats.request(response.status());
      if (response.status() == 100) {
        stats.mismatch("invited-oversized-body", label(Case.EXPECT_CONTINUE_OVER_CAP) + ": 100 Continue for a body over the cap");
      } else if (response.status() != 413) {
        stats.mismatch("wrong-status", label(Case.EXPECT_CONTINUE_OVER_CAP) + ": expected 413 got " + response);
      } else if (!"0".equals(response.header("Content-Length")) || !"close".equalsIgnoreCase(response.header("Connection"))) {
        stats.mismatch("wrong-framing", label(Case.EXPECT_CONTINUE_OVER_CAP) + ": 413 without Content-Length: 0 and Connection: close: " + response);
      } else if (expectClosed(in, Case.EXPECT_CONTINUE_OVER_CAP, "the 413")) {
        stats.ok();
        passed(stats, Case.EXPECT_CONTINUE_OVER_CAP);
      }
    }
  }

  /// A request line with no target and no version: 400 and the close where documented, skipped
  /// where the README says nothing.
  private void malformedLine() throws IOException {
    final var expectation = backend.malformed();
    if (expectation == null) {
      skipped(stats, Case.MALFORMED_LINE, "not documented");
      return;
    }
    attempted(stats, Case.MALFORMED_LINE);
    try (final var socket = connect()) {
      final var in = new BufferedInputStream(socket.getInputStream());
      write(socket, RawHttp.ascii("BADLINE\r\n\r\n"));
      final RawHttp.Response response;
      try {
        response = RawHttp.readResponse(in);
      } catch (final RawHttp.ClosedException e) {
        stats.mismatch("closed-without-status", label(Case.MALFORMED_LINE) + ": " + e.getMessage());
        return;
      }
      stats.request(response.status());
      if (!expectation.accepts(response.status())) {
        stats.mismatch("wrong-status", label(Case.MALFORMED_LINE) + ": expected " + expectation.statuses() + " got " + response);
        return;
      }
      stats.ok();
      if (afterwards(socket, in, expectation.closes(), Case.MALFORMED_LINE, "the " + response.status())) {
        passed(stats, Case.MALFORMED_LINE);
      }
    }
  }

  /// `GET /ping HTTP/1.0` with a `Host` header: answered and closed, or Helidon's 505.
  private void http10() throws IOException {
    final var expectation = backend.http10();
    attempted(stats, Case.HTTP10);
    try (final var socket = connect()) {
      final var in = new BufferedInputStream(socket.getInputStream());
      write(socket, RawHttp.ascii("GET /ping HTTP/1.0\r\n" + hostHeader() + "\r\n"));
      final var response = RawHttp.readResponse(in);
      if (answered(Case.HTTP10, expectation, response)
          && afterwards(socket, in, expectation.closes(), Case.HTTP10, "the HTTP/1.0 response")) {
        passed(stats, Case.HTTP10);
      }
    }
  }

  /// A head declaring 64 KiB, 4 KiB of it sent, then the socket abandoned — alternately with
  /// an orderly close (FIN) and with a reset (`SO_LINGER 0`, RST), since a server may take the
  /// two apart. The reset is paced a few milliseconds behind the flush so the prefix is on the
  /// wire before it, and the assertion is the server's survival: a fresh `GET /ping` must
  /// still be answered.
  private void abortMidBody() throws IOException {
    attempted(stats, Case.ABORT_MID_BODY);
    final boolean reset = (++aborts & 1) == 0;
    final var socket = connect();
    try {
      final var partial = new byte[ABORT_SENT];
      rnd.nextBytes(partial);
      write(socket, RawHttp.ascii("POST /echo HTTP/1.1\r\n" + hostHeader()
          + "Content-Type: application/octet-stream\r\nContent-Length: " + ABORT_DECLARED + "\r\n\r\n"), partial);
      pace();
      if (reset) {
        socket.setSoLinger(true, 0);
      }
    } finally {
      socket.close();
    }
    stats.count(reset ? "case:abort_mid_body:rst" : "case:abort_mid_body:fin");
    try (final var probe = connect()) {
      final var in = new BufferedInputStream(probe.getInputStream());
      write(probe, pingRequest());
      final var ping = RawHttp.readResponse(in);
      stats.request(ping.status());
      if (ping.status() == 200 && SoakServer.PING_JSON.equals(ping.bodyText())) {
        stats.ok();
        passed(stats, Case.ABORT_MID_BODY);
      } else {
        stats.mismatch("wrong-status", label(Case.ABORT_MID_BODY) + ": GET /ping after the abandoned upload answered " + ping);
      }
    }
  }

  private void pace() throws IOException {
    try {
      Thread.sleep(ABORT_PACE_MIN_MILLIS + rnd.nextInt(ABORT_PACE_SPREAD_MILLIS + 1));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("interrupted while pacing an abandoned upload");
    }
  }

  /// `GET http://host:port/ping HTTP/1.1` with a `Host` naming the same authority: refused
  /// 400 or reduced to its path and served, per backend.
  private void absoluteForm() throws IOException {
    final var expectation = backend.absoluteForm();
    if (expectation == null) {
      skipped(stats, Case.ABSOLUTE_FORM, "not documented");
      return;
    }
    attempted(stats, Case.ABSOLUTE_FORM);
    try (final var socket = connect()) {
      final var in = new BufferedInputStream(socket.getInputStream());
      final var authority = host + ':' + port;
      write(socket, RawHttp.ascii("GET http://" + authority + "/ping HTTP/1.1\r\nHost: " + authority + "\r\n\r\n"));
      final var response = RawHttp.readResponse(in);
      if (answered(Case.ABSOLUTE_FORM, expectation, response)
          && afterwards(socket, in, expectation.closes(), Case.ABSOLUTE_FORM, "the " + response.status())) {
        passed(stats, Case.ABSOLUTE_FORM);
      }
    }
  }

  /// `GET /ping HTTP/1.1` with no `Host`: 400 where the README says the header is required,
  /// served where it says it is not.
  private void noHost() throws IOException {
    final var expectation = backend.noHost();
    attempted(stats, Case.NO_HOST);
    try (final var socket = connect()) {
      final var in = new BufferedInputStream(socket.getInputStream());
      write(socket, RawHttp.ascii("GET /ping HTTP/1.1\r\n\r\n"));
      final var response = RawHttp.readResponse(in);
      if (answered(Case.NO_HOST, expectation, response)
          && afterwards(socket, in, expectation.closes(), Case.NO_HOST, "the " + response.status())) {
        passed(stats, Case.NO_HOST);
      }
    }
  }
}
