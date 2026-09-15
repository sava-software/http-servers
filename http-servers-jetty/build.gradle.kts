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

// Jetty's ThreadPoolBudget refuses to start a connector when the pool has no thread beyond its
// leases, which a pool of one thread per processor hit on one to three processors. A JVM cannot
// change its processor count after launch, so these run JettyThreadBudgetTest in JVMs that see
// exactly that many. Each task also pins Jetty's own count: ProcessorUtils reads the system
// property before the environment variable of the same name, so a JETTY_AVAILABLE_PROCESSORS
// inherited from the environment -- a legitimate setting in a virtualized host -- cannot leave
// Jetty counting processors the JVM was not launched with, and cannot fail 'check' there.
val smallHostTests = (1..3).map { processors ->
  tasks.register<Test>("smallHostTest$processors") {
    group = "verification"
    description = "Runs JettyThreadBudgetTest in a JVM that sees $processors available processor(s)."
    // the same whitebox module-path launch as 'test' (--patch-module and friends)
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    jvmArgumentProviders.addAll(tasks.test.get().jvmArgumentProviders)
    useJUnitPlatform()
    filter.includeTestsMatching("software.sava.http_servers.jetty.JettyThreadBudgetTest")
    jvmArgs("-XX:ActiveProcessorCount=$processors")
    systemProperty("JETTY_AVAILABLE_PROCESSORS", processors)
    systemProperty("smallHost.availableProcessors", processors)
  }
}
tasks.check { dependsOn(smallHostTests) }

hardening {
  mutation.register("dispatch") {
    // controller routing (404/405/preflight/CORS), request/response bridging and builder
    // wiring - killed through real socket round trips
    targetClasses = listOf("software.sava.http_servers.jetty.*")
    excludedClasses = listOf("software.sava.http_servers.jetty.*Test*")
    targetTests = "software.sava.http_servers.jetty.*Test*"
    // NAKED_RECEIVER: jetty's HttpFields.Mutable.put returns the receiver, so
    // VoidMethodCallMutator never fires on header writes; this makes them expressible.
    // Trial 2026-07-22: +10 mutants, all killed (2 needed errorResponsesAreJson).
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
}
