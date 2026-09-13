// Operational soak harness, not a library: no hardening plugin (there is no unit oracle for a
// load generator — see AGENTS.md) and not published (gradle/aggregation lists the published
// modules). The backends are runtime-only so ServiceLoader can discover any of them by name.
dependencies {
  project(":http-servers-core")
  runtimeOnly(project(":http-servers-jdk"))
  runtimeOnly(project(":http-servers-jetty"))
  runtimeOnly(project(":http-servers-fusionauth"))
  runtimeOnly(project(":http-servers-helidon"))
  runtimeOnly(project(":http-servers-netty"))
}

// soak.sh launches `java` directly (the java-module plugin adds no run task): this writes the
// module's runtime module path to build/soak/module-path.txt and the toolchain launcher the
// module was compiled for to build/soak/java.txt.
val soakModulePath by tasks.registering {
  group = "build"
  description = "Writes the runtime module path and the toolchain java launcher under build/soak for soak.sh."
  val runtimeClasspath = sourceSets.main.get().runtimeClasspath
  val launcher = javaToolchains.launcherFor(java.toolchain)
  val modulePathFile = layout.buildDirectory.file("soak/module-path.txt")
  val javaFile = layout.buildDirectory.file("soak/java.txt")
  inputs.files(runtimeClasspath)
  outputs.files(modulePathFile, javaFile)
  doLast {
    modulePathFile.get().asFile.writeText(runtimeClasspath.files.joinToString(File.pathSeparator) { it.absolutePath })
    javaFile.get().asFile.writeText(launcher.get().executablePath.asFile.absolutePath)
  }
}
