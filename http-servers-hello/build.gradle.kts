plugins {
  id("software.sava.build.feature.hardening")
}

dependencies {
  project(":http-servers-core")
//  project(":http-servers-jdk")
//  project(":http-servers-jetty")
//  project(":http-servers-fusionauth")
  runtimeOnly(project(":http-servers-jdk"))
  runtimeOnly(project(":http-servers-jetty"))
  runtimeOnly(project(":http-servers-fusionauth"))
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  requires("org.junit.jupiter.params")
  requires("java.net.http")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("hello") {
    // the demo bootstrap: ServiceLoader discovery by factory name and the wiring
    // include/exclude flow, killed through round trips against all three backends
    targetClasses = listOf("software.sava.http_servers.hello.*")
    excludedClasses = listOf(
      "software.sava.http_servers.hello.*Test*",
      "software.sava.http_servers.hello.Entrypoint"
    )
    declineExclusionAudit(
      "software.sava.http_servers.hello.Entrypoint",
      "thin main wrapper — a port-argument default and an eternal sleep; the boot flow it " +
          "wraps (factory discovery, wiring, server start) is HelloServer, mutated by this " +
          "suite and killed through HelloServerTests round trips against all three backends"
    )
    // Trial 2026-07-24: +1 receiver-returning call, killed by existing tests.
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
    targetTests = "software.sava.http_servers.hello.*Test*"
  }
}
