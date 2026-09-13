package software.sava.http_servers.soak;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Random;

/// Profile (d): opens and closes plain connections rapidly. Three in four are closed the
/// moment they are accepted; the fourth carries one request whose answer must end the
/// connection — alternately `GET /ping` with the request's own `Connection: close` and
/// `GET /close`, whose handler sets the header on its response — and verifies the answer
/// and the server's close, so the accept path is exercised empty and with a request on it,
/// and the README's "Connection persistence" rule from both sides.
final class Churner implements Runnable {

  private final int id;
  private final String host;
  private final int port;
  private final Stats stats;
  private final Run run;
  private final Random rnd;
  private int requests;

  Churner(final int id, final String host, final int port, final Stats stats, final Run run, final long seed) {
    this.id = id;
    this.host = host;
    this.port = port;
    this.stats = stats;
    this.run = run;
    this.rnd = new Random(seed);
  }

  @Override
  public void run() {
    while (run.running()) {
      final boolean withRequest = rnd.nextInt(4) == 0;
      try (final var socket = RawHttp.connect(host, port, RawHttp.READ_TIMEOUT_MILLIS)) {
        stats.count("connections");
        if (withRequest) {
          final boolean byHandler = (++requests & 1) == 0;
          final var op = byHandler ? "GET /close" : "GET /ping (Connection: close)";
          final var in = new BufferedInputStream(socket.getInputStream());
          socket.getOutputStream().write(RawHttp.ascii(byHandler
              ? "GET /close HTTP/1.1\r\nHost: " + host + "\r\n\r\n"
              : "GET /ping HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n"));
          socket.getOutputStream().flush();
          final var response = RawHttp.readResponse(in);
          stats.request(response.status());
          if (response.status() != 200) {
            stats.mismatch("wrong-status", "churner-" + id + ' ' + op + ": " + response);
          } else if (!SoakServer.PING_JSON.equals(response.bodyText())) {
            stats.mismatch("wrong-body", "churner-" + id + ' ' + op + ": " + response);
          } else if (!RawHttp.awaitClose(in)) {
            stats.mismatch("no-close-when-expected", "churner-" + id + ": still open after " + op);
          } else {
            stats.ok();
            stats.count(byHandler ? "closes_by_handler" : "closes_by_request");
          }
        } else {
          stats.count("connections_closed_empty");
        }
      } catch (final RawHttp.ClosedException e) {
        stats.mismatch("close-when-not-expected", "churner-" + id + ": " + e.getMessage());
      } catch (final SocketTimeoutException e) {
        stats.mismatch("timeout", "churner-" + id + ": " + e);
      } catch (final IOException | RuntimeException e) {
        stats.exception(e, "churner-" + id);
      }
      try {
        run.think(rnd, 10, 50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
