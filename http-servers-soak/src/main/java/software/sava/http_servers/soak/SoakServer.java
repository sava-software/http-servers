package software.sava.http_servers.soak;

import software.sava.http_servers.core.handlers.HandlerUtil;
import software.sava.http_servers.core.request.Request;
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.server.HttpServer;
import software.sava.http_servers.core.server.HttpServerBuilder;
import software.sava.http_servers.core.server.HttpServerBuilderFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/// The soak target: boots one backend by factory simple name with a fixed set of routes,
/// prints `PORT=<n>` once it is accepting, then samples the process to stdout as CSV until
/// it is terminated — heap, GC, threads, descriptors, the JUL counters and, from a
/// [JfrCounters] stream that covers the whole run rather than the recording's disk ring, the
/// `jdk.VirtualThreadSubmitFailed` and `jdk.VirtualThreadPinned` counts. `SIGTERM` runs the
/// shutdown hook, which prints a last sample and stops the server.
///
/// `main` takes the factory simple name (default `NettyBuilderFactory`), the port (default
/// 0: the abstraction cannot report a bound port, so 0 makes this class pick a free one
/// itself, probe-and-rebind with a retry when the race is lost), the sampling interval in
/// seconds (default 30) and the bind host (default `127.0.0.1`).
public final class SoakServer {

  static final String DEFAULT_FACTORY = "NettyBuilderFactory";
  static final String DEFAULT_HOST = "127.0.0.1";
  static final String PING_JSON = "{\"ping\":\"pong\"}";
  static final String NB_JSON = "{\"nb\":true}";
  static final int MAX_SLOW_MS = 2_000;
  static final String CSV_HEADER =
      "epoch_s,heap_used,heap_committed,nonheap_used,gc_count,threads_live,threads_peak,fds,leaks,severe,submit_failed,pinned";

  private static final Object OUT_LOCK = new Object();

  public static void main(final String[] args) throws Exception {
    final var factoryName = args.length > 0 ? args[0] : DEFAULT_FACTORY;
    final int port = args.length > 1 ? Integer.parseInt(args[1]) : 0;
    final int intervalSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 30;
    final var host = args.length > 3 ? args[3] : DEFAULT_HOST;
    if (intervalSeconds <= 0) {
      throw new IllegalArgumentException("interval seconds must be positive: " + intervalSeconds);
    }

    final var counters = LogCounters.install();
    final var executor = Executors.newVirtualThreadPerTaskExecutor();
    final var started = startOnFreePort(factoryName, host, port, executor);
    awaitListening(host, started.port());
    // only once the server is up: the stream's thread is not a daemon, so started earlier it
    // would keep a JVM whose start-up failed alive until soak.sh gave up on PORT=
    final var jfrCounters = JfrCounters.start();

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      print(sample(counters, jfrCounters));
      System.err.println("SoakServer stopping " + factoryName + " on " + host + ':' + started.port());
      try {
        started.server().stop();
      } catch (final Exception e) {
        System.err.println("SoakServer stop failed: " + e);
      }
    }, "soak-shutdown"));

    System.err.println("SoakServer " + factoryName + " listening on " + host + ':' + started.port()
        + ", sampling every " + intervalSeconds + " s");
    print("PORT=" + started.port());
    print(CSV_HEADER);
    print(sample(counters, jfrCounters));
    for (; ; ) {
      Thread.sleep(TimeUnit.SECONDS.toMillis(intervalSeconds));
      print(sample(counters, jfrCounters));
    }
  }

  /// Discovers the backend by factory simple name, registers the soak routes and starts it on
  /// `host:port` with `executor` as the blocking-handler executor.
  public static HttpServer start(final String factoryName,
                                 final String host,
                                 final int port,
                                 final Executor executor) throws Exception {
    final var factory = ServiceLoader.load(HttpServerBuilderFactory.class)
        .stream()
        .filter(provider -> provider.type().getSimpleName().equals(factoryName))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No HttpServerBuilderFactory found matching: " + factoryName))
        .get();
    final var builder = factory.createBuilder();
    register(builder);
    final var server = builder.createServer(executor, host, port);
    server.start();
    return server;
  }

  /// The routes the load generator drives. `/ping`, `/nb`, `/nocontent` and `/close` are the
  /// inline paths (cached bytes and non-blocking handlers: a 204 with no body, and the ping
  /// body under a handler-set `Connection: close`), everything else is a blocking route on
  /// the executor.
  static void register(final HttpServerBuilder builder) {
    final var ping = PING_JSON.getBytes(StandardCharsets.US_ASCII);
    builder.cachedQueryHandler("/ping", () -> ping);
    builder.nonBlockingQueryHandler("/nb", request -> HttpResponse.json(NB_JSON));
    builder.nonBlockingQueryHandler("/nocontent", request -> HttpResponse.response(204, "application/json", new byte[0]));
    builder.nonBlockingQueryHandler("/close", request -> HttpResponse.json(PING_JSON).withHeader("Connection", "close"));
    builder.blockingQueryPost("/echo", request -> HttpResponse.response(contentTypeOf(request), request.body()));
    builder.blockingQueryPost("/seq", request -> HttpResponse.response("text/plain", request.body()));
    builder.blockingQueryHandler("/slow", request -> {
      final int ms = Math.clamp(HandlerUtil.parseParam(request.query(), "ms=", 0), 0, MAX_SLOW_MS);
      try {
        Thread.sleep(ms);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while sleeping " + ms + " ms", e);
      }
      return HttpResponse.json("{\"slept\":" + ms + '}');
    });
    builder.blockingQueryHandler("/big", request -> {
      final int n = Math.clamp(HandlerUtil.parseParam(request.query(), "n=", 0), 0, SoakBytes.MAX_BIG);
      return HttpResponse.response("application/octet-stream", SoakBytes.big(n));
    });
    builder.blockingQueryHandler("/fail", request -> {
      throw new IllegalStateException("soak /fail: deliberate handler failure");
    });
  }

  private static String contentTypeOf(final Request request) {
    final var contentType = request.header("Content-Type");
    return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
  }

  record Started(HttpServer server, int port) {
  }

  static Started startOnFreePort(final String factoryName,
                                 final String host,
                                 final int port,
                                 final Executor executor) throws Exception {
    if (port != 0) {
      return new Started(start(factoryName, host, port, executor), port);
    }
    for (int attempt = 0; ; ++attempt) {
      final int candidate = freePort(host);
      try {
        return new Started(start(factoryName, host, candidate, executor), candidate);
      } catch (final Exception e) {
        if (attempt == 4 || !lostThePortRace(e)) {
          throw e;
        }
        System.err.println("port " + candidate + " was taken between probe and bind, retrying: " + e);
      }
    }
  }

  private static int freePort(final String host) throws IOException {
    try (final var socket = new ServerSocket()) {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress(host, 0));
      return socket.getLocalPort();
    }
  }

  private static boolean lostThePortRace(final Exception e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof BindException || cause instanceof ConnectException) {
        return true;
      }
    }
    return false;
  }

  /// `start()` has returned on every backend once the listener is bound, but `PORT=` is the
  /// load generator's cue, so it is printed only after a real connect succeeds.
  private static void awaitListening(final String host, final int port) throws Exception {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    for (; ; ) {
      try (final var socket = new Socket()) {
        socket.connect(new InetSocketAddress(host, port), 1_000);
        return;
      } catch (final IOException e) {
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException("server on " + host + ':' + port + " never accepted a connection", e);
        }
        Thread.sleep(50);
      }
    }
  }

  /// One `server.csv` row: the JVM's own heap, GC, thread and descriptor readings, the JUL
  /// counters, and the whole-run virtual-thread event counters from [JfrCounters].
  static String sample(final LogCounters counters, final JfrCounters jfrCounters) {
    final var memory = ManagementFactory.getMemoryMXBean();
    final var heap = memory.getHeapMemoryUsage();
    final var nonHeap = memory.getNonHeapMemoryUsage();
    long gcCount = 0;
    for (final var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      final long count = gc.getCollectionCount();
      if (count > 0) {
        gcCount += count;
      }
    }
    final var threads = ManagementFactory.getThreadMXBean();
    return System.currentTimeMillis() / 1_000
        + "," + heap.getUsed()
        + "," + heap.getCommitted()
        + "," + nonHeap.getUsed()
        + "," + gcCount
        + "," + threads.getThreadCount()
        + "," + threads.getPeakThreadCount()
        + "," + openFileDescriptors()
        + "," + counters.leaks()
        + "," + counters.severe()
        + "," + jfrCounters.submitFailed()
        + "," + jfrCounters.pinned();
  }

  /// `/dev/fd` lists the process's open descriptors on macOS and Linux alike; listing it
  /// opens one descriptor of its own for the duration, the same on every sample.
  static int openFileDescriptors() {
    final var entries = new File("/dev/fd").list();
    return entries == null ? -1 : entries.length;
  }

  private static void print(final String line) {
    synchronized (OUT_LOCK) {
      System.out.println(line);
      System.out.flush();
    }
  }

  private SoakServer() {
  }
}
