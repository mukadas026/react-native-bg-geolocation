plugins {
  kotlin("jvm") version "2.0.21"
}

repositories {
  mavenCentral()
}

sourceSets {
  main {
    kotlin.srcDir("../src/main/java/com/bggeolocation/core")
  }
}

dependencies {
  testImplementation(kotlin("test"))
  testImplementation("org.json:json:20240303")
}

tasks.test {
  useJUnitPlatform()
  systemProperty("conformance.dir", file("../../conformance/fixtures").absolutePath)
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
