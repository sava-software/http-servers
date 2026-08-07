package software.sava.http_servers.core.server;

/// A started server holds a listening socket and the threads behind it, so every backend
/// must offer a way to give them back. [#stop()] is the explicit lifecycle call;
/// [AutoCloseable] is implemented in terms of it so a scoped server — a test, a probe, a
/// short-lived tool — can be managed with try-with-resources.
///
/// Stopping is immediate rather than graceful: in-flight exchanges are not waited for. The
/// backends differ in what they can promise here, so no grace period is exposed rather than
/// documenting one this abstraction cannot keep. Calling [#stop()] on a server that never
/// started, or that failed to start, is a no-op on every backend.
public interface HttpServer extends AutoCloseable {

  void start() throws Exception;

  void stop() throws Exception;

  @Override
  default void close() throws Exception {
    stop();
  }
}
