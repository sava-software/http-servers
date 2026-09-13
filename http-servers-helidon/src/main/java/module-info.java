import software.sava.http_servers.core.server.HttpServerBuilderFactory;
import software.sava.http_servers.helidon.HelidonBuilderFactory;

module software.sava.http_servers.helidon {
  requires transitive software.sava.http_servers.core;

  // The backend is reached only through HttpServerBuilderFactory (the module exports no
  // package and the builder is package-private), matching the jdk and fusionauth backends
  // and the sibling netty module — so io.helidon.webserver is a plain requires, not
  // transitive: no exported type names a Helidon type.
  requires io.helidon.webserver;
  // The adapter uses types from each of these directly, so each needs its own requires:
  // implied readability through io.helidon.webserver is not a substitute, and
  // checkModuleDirectivesScope enforces that. HeaderNames/Method (io.helidon.http), the
  // UriQuery behind request.query() (io.helidon.common.uri) and the ReadableEntity behind
  // request.content() (io.helidon.http.media). The latter two are consumed through `var`,
  // so they appear in no import line.
  requires io.helidon.http;
  requires io.helidon.common.uri;
  requires io.helidon.http.media;

  provides HttpServerBuilderFactory with HelidonBuilderFactory;
}
