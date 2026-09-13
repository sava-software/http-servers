package software.sava.http_servers.soak;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// The silent-connection probe, on its own worker so the abuser's cycle is not held for the
/// documented timeout each check takes: a connection that goes quiet must be closed by the
/// server once that timeout has elapsed — within a margin that covers the JDK's 10 s timer
/// tick — and not noticeably before. The README documents the silence *between requests*
/// for the JDK, Jetty and Netty, so the probe sends one `GET /ping`, verifies it, and then
/// nothing; Netty also documents the timer from accept, so there every other probe sends
/// nothing at all. A check that could not complete before the run ends is skipped, once,
/// and counted as such; a backend with no documented timeout skips the case outright.
/// Records into the abuser's stats under the `case:idle:*` keys.
final class IdleProber implements Runnable {

  static final int MARGIN_MILLIS = 20_000;
  static final int EARLY_MILLIS = 2_000;

  private final int id;
  private final String host;
  private final int port;
  private final Backend backend;
  private final Stats stats;
  private final Run run;
  private final Random rnd;
  private int probes;

  IdleProber(final int id,
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
    final long idleTimeout = backend.idleTimeoutMillis();
    if (idleTimeout < 0) {
      Abuser.skipped(stats, Abuser.Case.IDLE, "no documented idle timeout");
      return;
    }
    final long bound = idleTimeout + MARGIN_MILLIS;
    final var label = Abuser.label("idle-" + id, Abuser.Case.IDLE);
    while (run.running()) {
      if (run.remainingMillis() < bound + 1_000) {
        Abuser.skipped(stats, Abuser.Case.IDLE, "not enough run time left");
        return;
      }
      final boolean afterAccept = backend.idleAfterAccept() && (++probes & 1) == 0;
      final var phase = afterAccept ? "after accept" : "after a response";
      Abuser.attempted(stats, Abuser.Case.IDLE);
      try (final var socket = RawHttp.connect(host, port, (int) bound)) {
        final var in = new BufferedInputStream(socket.getInputStream());
        if (!afterAccept) {
          socket.getOutputStream().write(RawHttp.ascii("GET /ping HTTP/1.1\r\nHost: " + host + "\r\n\r\n"));
          socket.getOutputStream().flush();
          final var response = RawHttp.readResponse(in);
          stats.request(response.status());
          if (response.status() != 200 || !SoakServer.PING_JSON.equals(response.bodyText())) {
            stats.mismatch("wrong-status", label + ": the GET /ping before the silence answered " + response);
            continue;
          }
          stats.ok();
        }
        final long start = System.nanoTime();
        final var outcome = RawHttp.closeOutcome(in);
        final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        switch (outcome) {
          case TIMEOUT -> stats.mismatch("no-close-when-expected", label + ": still open " + elapsed + " ms " + phase
              + " (documented idle timeout " + idleTimeout + " ms, margin " + MARGIN_MILLIS + " ms)");
          case BYTE -> stats.mismatch("unexpected-byte", label + ": the server wrote to a silent connection " + phase);
          case CLOSED -> {
            if (elapsed < idleTimeout - EARLY_MILLIS) {
              stats.mismatch("closed-early", label + ": closed after " + elapsed + " ms " + phase
                  + ", before the documented " + idleTimeout + " ms");
            } else {
              stats.count("idle_close_ms_sum", elapsed);
              stats.count(afterAccept ? "case:idle:after_accept" : "case:idle:after_response");
              Abuser.passed(stats, Abuser.Case.IDLE);
            }
          }
        }
      } catch (final RawHttp.ClosedException e) {
        stats.mismatch(e.reset ? "reset-when-not-expected" : "close-when-not-expected", label + ": " + e.getMessage());
      } catch (final SocketTimeoutException e) {
        stats.mismatch("timeout", label + ": " + e);
      } catch (final IOException | RuntimeException e) {
        stats.exception(e, label);
      }
      try {
        run.think(rnd, 50, 150);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
