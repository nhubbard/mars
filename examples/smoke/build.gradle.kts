plugins {
  base
}

tasks.named("check") {
  dependsOn(":instruction-lab:check", ":png-decoder:check")
}
