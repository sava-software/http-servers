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

tasks.test {
  // JdkConformanceTest's connection-leak oracle reads jdk.httpserver's private connection set by
  // reflection (nothing public observes it; see registeredConnections there). The test classes
  // are patched into this module on the module path here and run from the class path under
  // PIT's minions (minionJvmArgs below), so both targets are opened.
  jvmArgs("--add-opens=jdk.httpserver/sun.net.httpserver=software.sava.http_servers.jdk,ALL-UNNAMED")
}

hardening {
  mutation.register("dispatch") {
    // request routing (404/405/500), the raw-query and error-handling contract, and the
    // builder wiring behind them — killed through real socket round trips, so the suite is
    // slower per mutant than an in-process one; keep its targets to code the round-trip
    // tests can observe
    targetClasses = listOf("software.sava.http_servers.jdk.*")
    excludedClasses = listOf("software.sava.http_servers.jdk.*Test*")
    targetTests = "software.sava.http_servers.jdk.*Test*"
    // the leak oracle's reflection, opened for the class-path minions (see tasks.test above)
    minionJvmArgs = listOf("--add-opens=jdk.httpserver/sun.net.httpserver=ALL-UNNAMED")
  }
}
