rootProject.name = "mars-ide-smoke"

val repository = providers.gradleProperty("marsRepository")
  .map { file(it) }
  .getOrElse(sequenceOf(file("../mars-original"), file("../..")).firstOrNull { it.resolve("mars5/settings.gradle.kts").isFile }
    ?: error("Set -PmarsRepository to the path of the MARS checkout."))

includeBuild(repository.resolve("mars5"))
include("instruction-lab")
project(":instruction-lab").projectDir = repository.resolve("examples/smoke/instruction-lab")
include("png-decoder")
project(":png-decoder").projectDir = repository.resolve("examples/smoke/png-decoder")
