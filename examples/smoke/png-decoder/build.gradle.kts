plugins {
  kotlin("jvm")
}

repositories {
  mavenCentral()
}

dependencies {
  testImplementation("edu.missouristate:core:1.0-SNAPSHOT")
  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  jvmToolchain(25)
  compilerOptions { allWarningsAsErrors.set(true) }
}

tasks.test {
  useJUnitPlatform()
  inputs.dir("src/main/mips")
}
