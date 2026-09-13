package software.sava.http_servers.soak;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

/// Profile (a): a keep-alive `HttpClient` worker mixing the routed traffic — cached `/ping`,
/// non-blocking `/nb`, `/echo` with bodies up to 64 KiB verified byte for byte, `/big` up to
/// 256 KiB verified against the shared pattern, a short `/slow`, `/fail` expecting 500, an
/// unrouted path expecting 404, a wrong method expecting 405 with `Allow`, and the bodyless
/// `/nocontent` expecting 204 — recording the latency of every exchange per operation. The
/// `/slow` latency is the harness's own injected sleep, so it stays out of the pooled figure.
final class ClientWorker implements Runnable {

  static final int MAX_ECHO = 64 << 10;
  static final int MAX_BIG = 256 << 10;
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final String[] CONTENT_TYPES = {"application/octet-stream", "text/plain", "application/json"};
  private static final byte[] PING = SoakServer.PING_JSON.getBytes(StandardCharsets.US_ASCII);
  private static final byte[] NB = SoakServer.NB_JSON.getBytes(StandardCharsets.US_ASCII);

  private final int id;
  private final HttpClient client;
  private final String base;
  private final Stats stats;
  private final Run run;
  private final Random rnd;
  private long misses;

  ClientWorker(final int id,
               final HttpClient client,
               final String host,
               final int port,
               final Stats stats,
               final Run run,
               final long seed) {
    this.id = id;
    this.client = client;
    this.base = "http://" + host + ':' + port;
    this.stats = stats;
    this.run = run;
    this.rnd = new Random(seed);
  }

  @Override
  public void run() {
    while (run.running()) {
      final int pick = rnd.nextInt(100);
      try {
        if (pick < 28) {
          ping();
        } else if (pick < 46) {
          nb();
        } else if (pick < 68) {
          echo();
        } else if (pick < 83) {
          big();
        } else if (pick < 93) {
          slow();
        } else if (pick < 96) {
          fail();
        } else if (pick < 98) {
          notFound();
        } else if (pick < 99) {
          wrongMethod();
        } else {
          noContent();
        }
      } catch (final HttpTimeoutException e) {
        stats.mismatch("timeout", "client-" + id + ": " + e);
      } catch (final IOException | RuntimeException e) {
        stats.exception(e, "client-" + id);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      try {
        // after a failure as much as after a success, so a broken server is not hammered
        run.think(rnd, 10, 30);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private HttpRequest.Builder request(final String pathAndQuery) {
    return HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(REQUEST_TIMEOUT);
  }

  private HttpResponse<byte[]> send(final String op, final HttpRequest request) throws IOException, InterruptedException {
    stats.count("op:" + op);
    final long start = System.nanoTime();
    final var response = client.send(request, BodyHandlers.ofByteArray());
    stats.latency(op, System.nanoTime() - start, !"slow".equals(op));
    stats.request(response.statusCode());
    return response;
  }

  private void ping() throws IOException, InterruptedException {
    final var response = send("ping", request("/ping").GET().build());
    if (expectStatus(response, 200, "GET /ping") && expectBody(response, PING, "GET /ping")) {
      stats.ok();
    }
  }

  private void nb() throws IOException, InterruptedException {
    final var response = send("nb", request("/nb").GET().build());
    if (expectStatus(response, 200, "GET /nb") && expectBody(response, NB, "GET /nb")) {
      stats.ok();
    }
  }

  /// Half of the uploads are sent with `Transfer-Encoding: chunked` (an `InputStream`
  /// publisher has no known length) and half with a `Content-Length`, so both request
  /// framings a client can produce cross every backend's parser; chunked request bodies are
  /// where the backends have differed most (a chunked-encoding loop was fixed in java-http
  /// after the version on Central).
  private void echo() throws IOException, InterruptedException {
    final var body = new byte[rnd.nextInt(MAX_ECHO + 1)];
    rnd.nextBytes(body);
    final var contentType = CONTENT_TYPES[rnd.nextInt(CONTENT_TYPES.length)];
    final boolean chunked = rnd.nextBoolean();
    final var op = "POST /echo (" + body.length + " bytes, " + contentType + (chunked ? ", chunked)" : ")");
    final var publisher = chunked
        ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
        : HttpRequest.BodyPublishers.ofByteArray(body);
    final var response = send(chunked ? "echo_chunked" : "echo", request("/echo")
        .header("Content-Type", contentType)
        .POST(publisher)
        .build());
    if (expectStatus(response, 200, op) && expectBody(response, body, op)) {
      final var echoedType = response.headers().firstValue("Content-Type").orElse(null);
      if (contentType.equals(echoedType)) {
        stats.ok();
      } else {
        stats.mismatch("wrong-header", "client-" + id + ' ' + op + ": Content-Type echoed as " + echoedType);
      }
    }
  }

  private void big() throws IOException, InterruptedException {
    final int n = rnd.nextInt(MAX_BIG + 1);
    final var op = "GET /big?n=" + n;
    final var response = send("big", request("/big?n=" + n).GET().build());
    if (expectStatus(response, 200, op)) {
      final var body = response.body();
      if (body.length != n) {
        stats.mismatch("wrong-length", "client-" + id + ' ' + op + ": got " + body.length + " bytes");
      } else {
        final int at = SoakBytes.firstMismatch(body);
        if (at >= 0) {
          stats.mismatch("wrong-body", "client-" + id + ' ' + op + ": byte " + at + " departs from the pattern");
        } else {
          stats.ok();
        }
      }
    }
  }

  private void slow() throws IOException, InterruptedException {
    final int ms = 1 + rnd.nextInt(20);
    final var response = send("slow", request("/slow?ms=" + ms).GET().build());
    if (expectStatus(response, 200, "GET /slow?ms=" + ms)) {
      stats.ok();
    }
  }

  private void fail() throws IOException, InterruptedException {
    final var response = send("fail", request("/fail").GET().build());
    if (expectStatus(response, 500, "GET /fail")) {
      stats.ok();
    }
  }

  private void notFound() throws IOException, InterruptedException {
    final var path = "/missing/" + id + '-' + (++misses);
    final var response = send("not_found", request(path).GET().build());
    if (expectStatus(response, 404, "GET " + path)) {
      stats.ok();
    }
  }

  private void wrongMethod() throws IOException, InterruptedException {
    final var response = send("wrong_method", request("/ping").DELETE().build());
    if (expectStatus(response, 405, "DELETE /ping")) {
      if (response.headers().firstValue("Allow").isPresent()) {
        stats.ok();
      } else {
        stats.mismatch("wrong-header", "client-" + id + " DELETE /ping: 405 without Allow: " + response.headers().map());
      }
    }
  }

  private void noContent() throws IOException, InterruptedException {
    final var response = send("no_content", request("/nocontent").GET().build());
    if (expectStatus(response, 204, "GET /nocontent") && expectBody(response, new byte[0], "GET /nocontent")) {
      stats.ok();
    }
  }

  private boolean expectStatus(final HttpResponse<byte[]> response, final int expected, final String op) {
    if (response.statusCode() == expected) {
      return true;
    }
    stats.mismatch("wrong-status", "client-" + id + ' ' + op + ": expected " + expected + " got " + response.statusCode()
        + " body=" + RawHttp.abbreviate(new String(response.body(), StandardCharsets.ISO_8859_1)));
    return false;
  }

  private boolean expectBody(final HttpResponse<byte[]> response, final byte[] expected, final String op) {
    final var body = response.body();
    if (Arrays.equals(body, expected)) {
      return true;
    }
    stats.mismatch("wrong-body", "client-" + id + ' ' + op + ": expected " + expected.length + " bytes, got " + body.length
        + ": " + RawHttp.abbreviate(new String(body, StandardCharsets.ISO_8859_1)));
    return false;
  }
}
