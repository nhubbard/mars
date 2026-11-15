plugins {
  base
  kotlin("jvm") version "2.4.21" apply false
}

tasks.named("check") {
  dependsOn(":instruction-lab:check", ":png-decoder:check")
}
