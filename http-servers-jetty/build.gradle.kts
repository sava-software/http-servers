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
  runtimeOnly("org.junit.jupiter.engine")
}
