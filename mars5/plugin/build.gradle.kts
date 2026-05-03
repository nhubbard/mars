plugins {
  id("java")
}

group = "edu.missouristate"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(project(":core"))
  implementation("com.google.dagger:dagger:2.59.2")
  annotationProcessor("com.google.dagger:dagger-compiler:2.59.2")

  testImplementation(platform("org.junit:junit-bom:6.0.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
  manifest {
    attributes("Main-Class" to "edu.missouristate.mars.Mars")
  }
}


tasks.test {
  useJUnitPlatform()
}