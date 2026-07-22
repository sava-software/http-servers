plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("handlers") {
    // request routing and query parsing: the first code to touch every untrusted request
    targetClasses = listOf("software.sava.http_servers.core.handlers.*")
    excludedClasses = listOf("software.sava.http_servers.core.handlers.*Test*")
    targetTests = "software.sava.http_servers.core.handlers.*Test*"
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
  }
  mutation.register("response") {
    // response factories and header-copy semantics
    targetClasses = listOf("software.sava.http_servers.core.response.*")
    excludedClasses = listOf("software.sava.http_servers.core.response.*Test*")
    targetTests = "software.sava.http_servers.core.response.*Test*"
  }
  mutation.register("logging") {
    // placeholder formatting and caller resolution; emission is asserted through a capturing handler
    targetClasses = listOf("software.sava.http_servers.core.logging.*")
    excludedClasses = listOf("software.sava.http_servers.core.logging.*Test*")
    targetTests = "software.sava.http_servers.core.logging.*Test*"
  }
}
