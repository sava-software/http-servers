dependencies {
  project(":http-servers-core")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  requires("java.net.http")
  runtimeOnly("org.junit.jupiter.engine")
}
