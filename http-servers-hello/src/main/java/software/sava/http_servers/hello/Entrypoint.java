package software.sava.http_servers.hello;

public final class Entrypoint {

  static void main(final String[] args) throws Exception {
    final var factoryName = args.length == 0
        ? "JettyServerBuilderFactory"
        : args[0];
    HelloServer.start(factoryName, "localhost", 4242);
    Thread.sleep(Long.MAX_VALUE);
  }
}
