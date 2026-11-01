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
    // controller routing (404/405/preflight/CORS), per-connection response ordering,
    // request/response bridging, lifecycle and builder wiring - killed through real socket
    // round trips
    targetClasses = listOf("software.sava.http_servers.netty.*")
    excludedClasses = listOf("software.sava.http_servers.netty.*Test*")
    targetTests = "software.sava.http_servers.netty.*Test*"
    // NAKED_RECEIVER: Netty's HttpHeaders.set returns the receiver, so VoidMethodCallMutator
    // never fires on a header write; this makes them expressible (measured trial in
    // config/pitest/README.md).
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
}
