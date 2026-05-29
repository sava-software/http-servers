dependencies {
  project(":http-servers-core")
//  project(":http-servers-jdk")
//  project(":http-servers-jetty")
//  project(":http-servers-fusionauth")
  runtimeOnly(project(":http-servers-jdk"))
  runtimeOnly(project(":http-servers-jetty"))
  runtimeOnly(project(":http-servers-fusionauth"))
}
