rootProject.name = "mars5"
include("core")
include("cli")
include("datapath")
if (providers.gradleProperty("includeVenus").isPresent) {
  include("desktop")
}
include("plugin")
include("plugin-jvm-compiler")
