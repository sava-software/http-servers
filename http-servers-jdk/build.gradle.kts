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
    // request routing (404/405/500), the raw-query and error-handling contract, and the
    // builder wiring behind them — killed through real socket round trips, so the suite is
    // slower per mutant than an in-process one; keep its targets to code the round-trip
    // tests can observe
    targetClasses = listOf("software.sava.http_servers.jdk.*")
    excludedClasses = listOf("software.sava.http_servers.jdk.*Test*")
    targetTests = "software.sava.http_servers.jdk.*Test*"
  }
}
