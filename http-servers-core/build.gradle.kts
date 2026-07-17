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
    targetClasses = listOf(
      "software.sava.http_servers.core.handlers.HandlerUtil",
      "software.sava.http_servers.core.handlers.HandlerMapImpl",
      "software.sava.http_servers.core.handlers.HandlerLookup"
    )
    targetTests = "software.sava.http_servers.core.handlers.*Test*"
  }
}
