plugins {
  id("software.sava.build.feature.hardening")
}

dependencies {
  project(":http-servers-core")
  // https://mvnrepository.com/artifact/org.slf4j/slf4j-jdk14
  runtimeOnly("org.slf4j:slf4j-jdk14")
  runtimeOnly("org.eclipse.jetty.compression:jetty-compression-gzip")
//  runtimeOnly("org.eclipse.jetty.compression:jetty-compression-brotli")
//  runtimeOnly("org.eclipse.jetty.compression:jetty-compression-zstandard")
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
    targetClasses = listOf("software.sava.http_servers.jetty.*")
    excludedClasses = listOf("software.sava.http_servers.jetty.*Test*")
    targetTests = "software.sava.http_servers.jetty.*Test*"
  }
}
