package software.sava.http_servers.soak;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/// Profile (b): a raw-socket pipeliner. Each burst writes 2..8 requests in one call and reads
/// exactly as many responses, checking each against the request at its position. The shapes
/// are mixed so ordering is stressed across producers, not just across identical handler
/// calls: `POST /seq` (the body echoes its sequence number), the inline `/ping` and `/nb`,
/// `/slow?ms=N` with N descending along the burst so a server answering as handlers finish
/// would answer the later, faster request first, `/big?n=N` verified against the shared
/// pattern, an unrouted path (the controller's 404), a wrong method on a routed path (405)
/// and the bodyless `/nocontent` (204). A response that belongs to another element of the
/// burst is a `wrong-order` mismatch; anything else is `wrong-status` or `wrong-body`. One
/// burst in eight carries `Connection: close` on its last request and verifies the server's
/// EOF; another one in eight carries it before the last request: the responses up to and
/// including the closing one are read, and then the close must follow — with nothing
/// pipelined behind it answered where the README documents that (Netty), and answers that
/// arrive first merely counted elsewhere.
final class Pipeliner implements Runnable {

  static final int MIN_BURST = 2;
  static final int MAX_BURST = 8;
  static final int MAX_PIPELINED_BIG = 2048;
  static final int MAX_PIPELINED_SLOW_MS = 16;

  enum Shape {
    SEQ, PING, NB, SLOW, BIG, NOT_FOUND, WRONG_METHOD, NO_CONTENT
  }

  /// One pipelined request and the answer it must get; a null body is not checked.
  record Element(Shape shape, String describe, byte[] request, int status, byte[] body) {

    boolean matches(final RawHttp.Response response) {
      return response.status() == status && (body == null || Arrays.equals(body, response.body()));
    }

    String expected() {
      return status + (body == null ? "" : " with a " + body.length + "-byte body");
    }
  }

  private static final byte[] PING_BODY = SoakServer.PING_JSON.getBytes(StandardCharsets.US_ASCII);
  private static final byte[] NB_BODY = SoakServer.NB_JSON.getBytes(StandardCharsets.US_ASCII);
  private static final byte[] EMPTY = new byte[0];

  private final int id;
  private final String host;
  private final int port;
  private final Backend backend;
  private final Stats stats;
  private final Run run;
  private final Random rnd;
  private long seq;

  Pipeliner(final int id,
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
    Socket socket = null;
    InputStream in = null;
    try {
      while (run.running()) {
        boolean reconnect;
        try {
          if (socket == null) {
            socket = RawHttp.connect(host, port, RawHttp.READ_TIMEOUT_MILLIS);
            in = new BufferedInputStream(socket.getInputStream());
            stats.count("connections");
          }
          final int size = MIN_BURST + rnd.nextInt(MAX_BURST - MIN_BURST + 1);
          final int closeAt = closeIndex(size);
          final var burst = burst(size, closeAt);
          final var bytes = new ByteArrayOutputStream(size * 128);
          for (final var element : burst) {
            bytes.writeBytes(element.request());
          }
          socket.getOutputStream().write(bytes.toByteArray());
          socket.getOutputStream().flush();
          stats.count("bursts");
          final int answered = closeAt < 0 ? size : closeAt + 1;
          for (int i = 0; i < answered; ++i) {
            final var element = burst.get(i);
            final var response = RawHttp.readResponse(in);
            stats.request(response.status());
            if (element.matches(response)) {
              stats.ok();
            } else {
              final String kind;
              if (fromThisBurst(burst, i, response)) {
                kind = "wrong-order";
              } else {
                kind = response.status() == element.status() ? "wrong-body" : "wrong-status";
              }
              stats.mismatch(kind, "pipeliner-" + id + " position " + i + " of " + size + ": " + element.describe()
                  + " expected " + element.expected() + " got " + response + (closeAt >= 0 ? " (Connection: close at " + closeAt + ")" : ""));
            }
          }
          reconnect = closeAt >= 0;
          if (closeAt >= 0) {
            verifyClose(in, burst.get(closeAt), size - 1 - closeAt);
          }
        } catch (final RawHttp.ClosedException e) {
          stats.mismatch("close-when-not-expected", "pipeliner-" + id + ": " + e.getMessage());
          reconnect = true;
        } catch (final SocketTimeoutException e) {
          stats.mismatch("timeout", "pipeliner-" + id + ": " + e);
          reconnect = true;
        } catch (final IOException | RuntimeException e) {
          stats.exception(e, "pipeliner-" + id);
          reconnect = true;
        }
        if (reconnect) {
          RawHttp.closeQuietly(socket);
          socket = null;
        }
        run.think(rnd, 40, 80);
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      RawHttp.closeQuietly(socket);
    }
  }

  /// -1 for no close, `size - 1` one time in eight, and one time in eight an index with at
  /// least one request pipelined behind it.
  private int closeIndex(final int size) {
    final int roll = rnd.nextInt(8);
    if (roll == 0) {
      return size - 1;
    }
    if (roll == 1) {
      return rnd.nextInt(size - 1);
    }
    return -1;
  }

  private List<Element> burst(final int size, final int closeAt) {
    final var burst = new ArrayList<Element>(size);
    int slowMs = MAX_PIPELINED_SLOW_MS;
    for (int i = 0; i < size; ++i) {
      final boolean close = i == closeAt;
      final int pick = rnd.nextInt(16);
      final Element element;
      if (pick < 6) {
        final var body = Long.toString(++seq);
        element = new Element(Shape.SEQ, "POST /seq " + body, RawHttp.ascii("POST /seq HTTP/1.1\r\nHost: " + host
            + "\r\nContent-Type: text/plain\r\nContent-Length: " + body.length() + "\r\n"
            + (close ? "Connection: close\r\n" : "") + "\r\n" + body), 200, RawHttp.ascii(body));
      } else if (pick < 8) {
        element = bodyless(Shape.PING, "GET", "/ping", 200, PING_BODY, close);
      } else if (pick < 10) {
        element = bodyless(Shape.NB, "GET", "/nb", 200, NB_BODY, close);
      } else if (pick < 12) {
        element = bodyless(Shape.SLOW, "GET", "/slow?ms=" + slowMs, 200, RawHttp.ascii("{\"slept\":" + slowMs + '}'), close);
        slowMs = Math.max(1, slowMs - 4);
      } else if (pick < 13) {
        final int n = 1 + rnd.nextInt(MAX_PIPELINED_BIG);
        element = bodyless(Shape.BIG, "GET", "/big?n=" + n, 200, SoakBytes.big(n), close);
      } else if (pick < 14) {
        element = bodyless(Shape.NOT_FOUND, "GET", "/missing/" + (++seq), 404, null, close);
      } else if (pick < 15) {
        element = bodyless(Shape.WRONG_METHOD, "DELETE", "/ping", 405, null, close);
      } else {
        element = bodyless(Shape.NO_CONTENT, "GET", "/nocontent", 204, EMPTY, close);
      }
      burst.add(element);
      stats.count("shape:" + element.shape().name().toLowerCase(Locale.ROOT));
    }
    return burst;
  }

  private Element bodyless(final Shape shape,
                           final String method,
                           final String target,
                           final int status,
                           final byte[] body,
                           final boolean close) {
    return new Element(shape, method + ' ' + target, RawHttp.ascii(method + ' ' + target + " HTTP/1.1\r\nHost: " + host
        + "\r\n" + (close ? "Connection: close\r\n" : "") + "\r\n"), status, body);
  }

  private void verifyClose(final InputStream in, final Element closing, final int behind) throws IOException {
    final var outcome = RawHttp.closeOutcome(in);
    if (behind == 0) {
      if (outcome == RawHttp.CloseOutcome.CLOSED) {
        stats.count("closes_verified");
      } else {
        stats.mismatch("no-close-when-expected", "pipeliner-" + id + ": " + outcome + " instead of EOF after Connection: close on " + closing.describe());
      }
      return;
    }
    switch (outcome) {
      case CLOSED -> stats.count("mid_closes_verified");
      case TIMEOUT -> stats.mismatch("no-close-when-expected", "pipeliner-" + id + ": still open after Connection: close on "
          + closing.describe() + " with " + behind + " request(s) pipelined behind it");
      case BYTE -> {
        if (backend.pipelineCloseStrict()) {
          stats.mismatch("processed-past-close", "pipeliner-" + id + ": the server answered past Connection: close on "
              + closing.describe() + " (" + behind + " request(s) pipelined behind it)");
        } else {
          stats.count("answered_behind_close");
          in.readAllBytes();
          stats.count("mid_closes_verified");
        }
      }
    }
  }

  private static boolean fromThisBurst(final List<Element> burst, final int position, final RawHttp.Response response) {
    for (int j = 0; j < burst.size(); ++j) {
      if (j != position && burst.get(j).matches(response)) {
        return true;
      }
    }
    return false;
  }
}
