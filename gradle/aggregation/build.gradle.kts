plugins {
  id("software.sava.build.feature.publish-maven-central")
}

val idlClientModules = setOf(
  "core",
  "fusionauth",
  "helidon",
  "jdk",
  "jetty",
  "netty",
  "sava"
)

dependencies {
  for (module in idlClientModules) {
    nmcpAggregation(project(":http-servers-$module"))
  }
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  val publishTasks =
    idlClientModules.map { ":http-servers-$it:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository" }
  dependsOn(publishTasks)
}
