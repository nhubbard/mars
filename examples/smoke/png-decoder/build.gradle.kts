plugins {
  java
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

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

tasks.test {
  useJUnitPlatform()
  inputs.dir("src/main/mips")
}
