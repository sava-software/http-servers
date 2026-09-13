plugins {
  id("software.sava.build.feature.hardening")
}

dependencies {
  project(":http-servers-core")
  // The sava version catalog (solana-version-catalog) carries no Helidon entry yet, so the
  // vendor BOM pins every io.helidon.* module resolved from module-info here. The pin moves
  // to solana-version-catalog once it gains a Helidon row; drop this line then.
  implementation(platform("io.helidon:helidon-bom:4.5.4"))
  // H2C (HTTP/2 cleartext) is opt-in. Without helidon-webserver-http2 Helidon answers every
  // HTTP/1.0 request with a clean 505; with it on the module path an HTTP/1.0 request is
  // answered with nothing — the connection is closed with zero bytes when a Host header is
  // supplied and held open until the idle timeout when it is not (measured 2026-09-12 against
  // 4.5.4, module path, JDK 25.0.2). Consumers who want H2C add it themselves and keep it away
  // from HTTP/1.0 clients.
//  runtimeOnly("io.helidon.webserver:helidon-webserver-http2")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  requires("java.net.http")
  requires("java.logging")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("dispatch") {
    // controller routing (404/405/preflight/CORS), request/response bridging, the
    // build-in-start lifecycle and builder wiring - killed through real socket round trips
    targetClasses = listOf("software.sava.http_servers.helidon.*")
    excludedClasses = listOf("software.sava.http_servers.helidon.*Test*")
    targetTests = "software.sava.http_servers.helidon.*Test*"
    // NAKED_RECEIVER: Helidon's ServerResponse.status/header and WebServerConfig.Builder are
    // fluent (receiver-returning), so VoidMethodCallMutator never fires on a header write or a
    // listener setting; this makes them expressible. Trial 2026-09-12: +13 mutants, all 13
    // killed by existing tests (pitestMutatorTrial, recorded in config/pitest/README.md).
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
}
