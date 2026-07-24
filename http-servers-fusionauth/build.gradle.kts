plugins {
  id("software.sava.build.feature.hardening")
}

dependencies {
  project(":http-servers-core")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  requires("java.net.http")
  requires("java.logging")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("dispatch") {
    // controller routing (404/405/preflight/CORS), request/response bridging and builder
    // wiring - killed through real socket round trips
    targetClasses = listOf("software.sava.http_servers.fusionauth.*")
    excludedClasses = listOf(
      "software.sava.http_servers.fusionauth.*Test*",
      // owned by the loggerShim suite: the framework's own threads log through the shim,
      // so mutating it under socket tests can wedge the server past PIT's timeout
      "software.sava.http_servers.fusionauth.logging.*"
    )
    targetTests = "software.sava.http_servers.fusionauth.*Test*"
    // NAKED_RECEIVER makes dropped fluent calls (java-http's withListener/withLoggerFactory
    // chains) expressible. Trial 2026-07-24: +2 mutants; 1 killed by existing tests, the
    // logger-shim installation killed by frameworkLoggingFlowsThroughTheJulShim.
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  mutation.register("loggerShim") {
    // the java-http -> JUL level/emit mapping, killed by the in-process logger tests only
    targetClasses = listOf("software.sava.http_servers.fusionauth.logging.*")
    excludedClasses = listOf("software.sava.http_servers.fusionauth.logging.*Test*")
    targetTests = "software.sava.http_servers.fusionauth.logging.*Test*"
  }
}
