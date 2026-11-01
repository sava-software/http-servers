plugins {
  id("software.sava.build.feature.hardening")
}

dependencies {
  project(":http-servers-core")
}

// netty-codec-http declares netty-codec-compression in compile scope, but its module descriptor
// only `requires static io.netty.codec.compression`, and the classes that reference it
// (HttpContentCompressor and HttpContentDecompressor, the WebSocket permessage-deflate
// extension and the SPDY JZlib encoder, per jdeps) are ones this adapter never puts in a
// pipeline, so the jar never loads. The exclude sits on the derived io.netty:netty-codec-http
// edge rather than on a configuration, so the published POM and module metadata carry it on
// that edge alone and a consumer inherits it there and nowhere else. netty-handler stays:
// io.netty.codec.http requires it outright (HttpChunkedInput and the WebSocket handshake
// handler), so a module path without it does not resolve.
configurations.configureEach {
  dependencies.withType<ModuleDependency>().configureEach {
    if (group == "io.netty" && name == "netty-codec-http") {
      exclude(group = "io.netty", module = "netty-codec-compression")
    }
  }
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
