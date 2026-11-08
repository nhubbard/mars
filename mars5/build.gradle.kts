plugins {
  id("java")
  id("idea")
  id("com.diffplug.spotless") version "8.11.0"
}

group = "edu.missouristate"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.jspecify:jspecify:1.0.0")
  testImplementation(platform("org.junit:junit-bom:6.0.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.assertj:assertj-core:3.27.7")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

spotless {
  java {
    googleJavaFormat()
  }
}
subprojects {
  plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
      toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
  }
}
