package software.sava.http_servers.fusionauth;

import io.fusionauth.http.server.HTTPServer;
import software.sava.http_servers.core.server.HttpServer;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class FusionAuthHttpServer implements HttpServer {

  private final HTTPServer server;

  FusionAuthHttpServer(final HTTPServer server) {
    this.server = server;
  }

  @Override
  public void start() {
    // java-http's start() swallows a listener bind failure: it logs SEVERE (through the
    // JUL logger factory the builder installs), closes the threads that did start, and
    // returns normally — leaving the caller holding a dead server that accepts nothing.
    // The failure is observable only through that log call, and a port probe cannot
    // attribute a listening socket to *this* server (the port thief answers too). The
    // error is logged synchronously on this thread, so capture it here — filtered by
    // thread id so a concurrent start cannot cross-talk — and convert it back into the
    // throw the HttpServer contract expects.
    final var failure = new AtomicReference<Throwable>();
    final long threadId = Thread.currentThread().threadId();
    final var capture = new Handler() {
      @Override
      public void publish(final LogRecord record) {
        if (record.getLongThreadID() == threadId
            && record.getThrown() != null
            && record.getLevel().intValue() >= Level.SEVERE.intValue()) {
          failure.set(record.getThrown());
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    final var jul = Logger.getLogger(HTTPServer.class.getName());
    jul.addHandler(capture);
    try {
      server.start();
    } finally {
      jul.removeHandler(capture);
    }
    final var thrown = failure.get();
    if (thrown != null) {
      throw new IllegalStateException(
          "java-http failed to start a listener; its start() logs the failure instead of throwing", thrown);
    }
  }

  @Override
  public void stop() {
    // java-http models shutdown as Closeable rather than a stop(); it is idempotent and
    // safe on a server whose start() logged a bind failure and returned a dead instance.
    server.close();
  }
}
