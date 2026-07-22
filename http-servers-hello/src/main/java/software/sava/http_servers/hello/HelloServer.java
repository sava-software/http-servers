package software.sava.http_servers.hello;

import software.sava.http_servers.core.server.HttpServer;
import software.sava.http_servers.core.server.HttpServerBuilderFactory;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Executors;

/// Discovers a backend by factory simple name, wires the demo handlers and starts the
/// server. Extracted from [Entrypoint] so the bootstrap is testable against every backend.
public final class HelloServer {

  static final String HELLO_PATH = "/hello";
  static final String EXCLUDE_PATH = "/exclude";

  public static HttpServer start(final String factoryName, final String host, final int port) throws Exception {
    final var factory = ServiceLoader.load(HttpServerBuilderFactory.class)
        .stream()
        .filter(provider -> provider.type().getSimpleName().equals(factoryName))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No HttpServerBuilderFactory found matching: " + factoryName))
        .get();
    final var serverBuilder = factory.createBuilder();
    final var handlerWiring = serverBuilder.wireNonExcludedHandlers(
        Map.of(HelloHandlerGroup.HELLO, Set.of(EXCLUDE_PATH))
    );

    final var helloHandler = new HelloHandler();
    if (handlerWiring.includePath(HelloHandlerGroup.HELLO, HELLO_PATH)) {
      serverBuilder.nonBlockingQueryHandler(HELLO_PATH, helloHandler);
    }
    handlerWiring.queryNonBlockingGet(HelloHandlerGroup.HELLO, EXCLUDE_PATH, helloHandler);

    final var server = serverBuilder.createServer(Executors.newVirtualThreadPerTaskExecutor(), host, port);
    server.start();
    return server;
  }

  private HelloServer() {
  }
}
