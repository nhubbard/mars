plugins { id("java-library") }
group = "edu.missouristate"
version = "1.0-SNAPSHOT"
dependencies { api(project(":core")) }

repositories { mavenCentral() }
dependencies {
 testImplementation(platform("org.junit:junit-bom:6.0.0"))
 testImplementation("org.junit.jupiter:junit-jupiter")
 testImplementation("org.assertj:assertj-core:3.26.3")
 testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
sourceSets.test { resources.srcDir("../core/src/test/resources") }
tasks.test { useJUnitPlatform() }
