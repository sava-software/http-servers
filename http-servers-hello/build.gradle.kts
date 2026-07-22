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
      // thin main wrapper: an argument default and an eternal sleep, unreachable in-harness
      "software.sava.http_servers.hello.Entrypoint"
    )
    targetTests = "software.sava.http_servers.hello.*Test*"
  }
}
