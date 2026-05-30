package software.sava.http_servers.hello;

import software.sava.http_servers.core.server.HttpServerBuilderFactory;

import java.util.ServiceLoader;
import java.util.concurrent.Executors;

public final class Entrypoint {

  private static final String HELLO_PATH = "/hello";

  static void main(final String[] args) throws Exception {
    final var factoryName = args.length == 0
        ? "FusionAuthBuilderFactory"
        : args[0];

    final var factory = ServiceLoader.load(HttpServerBuilderFactory.class)
        .stream()
        .filter(provider -> provider.type().getSimpleName().equals(factoryName))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No HttpServerBuilderFactory found matching: " + factoryName))
        .get();
    final var serverBuilder = factory.createBuilder();
    final var handlerWiring = serverBuilder.wireHandlers(
        null,
        null
    );

    if (handlerWiring.includeGroup(HelloHandlerGroup.HELLO)) {
      if (handlerWiring.includePath(HelloHandlerGroup.HELLO, HELLO_PATH)) {
        serverBuilder.nonBlockingQueryHandler(HELLO_PATH, new HelloHandler());
      }
    }

    try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var server = serverBuilder.createServer(executor, "localhost", 4242);

      server.start();

      Thread.sleep(Long.MAX_VALUE);
    }
  }
}
