plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  requires("org.junit.jupiter.params")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("handlers") {
    // request routing and query parsing: the first code to touch every untrusted request
    targetClasses = listOf("software.sava.http_servers.core.handlers.*")
    excludedClasses = listOf("software.sava.http_servers.core.handlers.*Test*")
    targetTests = "software.sava.http_servers.core.handlers.*Test*"
    // NAKED_RECEIVER makes dropped fluent calls (String slicing, list building) expressible.
    // Trial 2026-07-22: +5 mutants, all killed by existing tests.
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  fuzz.register("formatPlaceholders") {
    // generative oracle: token streams carry their own expected output; arbitrary bytes
    // probe the never-throws contract
    targetClass = "software.sava.http_servers.core.logging.FormatPlaceholdersFuzz"
    maxLen = 256
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/formatPlaceholders")
  }
  fuzz.register("pathCanonicalizer") {
    // generative oracle: token streams carry their own expected canonical form; arbitrary
    // bytes probe never-throws and the no-uncanonical-segment properties routing relies on
    targetClass = "software.sava.http_servers.core.handlers.PathCanonicalizerFuzz"
    maxLen = 256
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/pathCanonicalizer")
  }
  fuzz.register("handlerUtil") {
    // differential: the hand-rolled boundary scanner against a naive split-based reference;
    // query strings are small and unstructured enough for from-scratch mutation, the seeds
    // just aim it at the interesting shapes (encoded delimiters, malformed escapes, lists)
    targetClass = "software.sava.http_servers.core.handlers.HandlerUtilFuzz"
    maxLen = 256
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/handlerUtil")
  }
  mutation.register("wiring") {
    // handler-group include/exclude filtering: decides which handlers get registered
    targetClasses = listOf("software.sava.http_servers.core.server.BaseHandlerWiring")
    targetTests = "software.sava.http_servers.core.server.BaseHandlerWiring*Test*"
  }
  mutation.register("server") {
    // builder registration/aliasing, controller snapshot wiring, and the builder-factory lookup
    targetClasses = listOf("software.sava.http_servers.core.server.*")
    excludedClasses = listOf(
      "software.sava.http_servers.core.server.*Test*",
      // owned by the wiring suite
      "software.sava.http_servers.core.server.BaseHandlerWiring"
    )
    targetTests = "software.sava.http_servers.core.server.*Test*"
    // Trial 2026-07-24: +1 receiver-returning call, killed by existing tests.
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  mutation.register("response") {
    // response factories and header-copy semantics, plus the request-side half of the
    // message contract: core.request holds only the Request interface, whose sole
    // production consumer is this package's QueryHandler (Request -> HttpResponse), and
    // whose implementations are per-adapter and owned by each adapter's dispatch suite.
    // Targeted rather than declined so a default method added to Request later joins the
    // mutated population by default instead of escaping it silently (an all-abstract
    // interface contributes no mutants today). targetTests stays narrow on purpose —
    // kills come only from targetTests, and widening it to core.*Test* would re-run every
    // core test per response mutant and blur which suite owns a kill; the first default
    // method on Request is owed a test in this suite's test scope.
    targetClasses = listOf(
      "software.sava.http_servers.core.response.*",
      "software.sava.http_servers.core.request.*"
    )
    excludedClasses = listOf(
      "software.sava.http_servers.core.response.*Test*",
      "software.sava.http_servers.core.request.*Test*"
    )
    targetTests = "software.sava.http_servers.core.response.*Test*"
  }
  mutation.register("logging") {
    // placeholder formatting and caller resolution; emission is asserted through a capturing handler
    targetClasses = listOf("software.sava.http_servers.core.logging.*")
    excludedClasses = listOf("software.sava.http_servers.core.logging.*Test*")
    targetTests = "software.sava.http_servers.core.logging.*Test*"
    // NAKED_RECEIVER makes dropped StringBuilder.append chains expressible.
    // Trial 2026-07-22: +7 mutants, all killed by existing tests.
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
}
