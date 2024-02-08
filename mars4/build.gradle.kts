import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    java
    idea
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
    jacoco
}

group = "edu.missouristate"
version = "4.6-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Java
    compileOnly("org.jetbrains:annotations:24.0.0")
    // Kotlin
    implementation(kotlin("stdlib-jdk8"))
    // Testing
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.9.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.9.0")
}

sourceSets {
    main {
        resources {
            srcDir("src/main/resources/")
            include("*.png")
            include("*.jpg")
        }
    }
}

application {
    mainClass.set("edu.missouristate.mars.Mars")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    test {
        useJUnitPlatform()
        finalizedBy(jacocoTestReport)
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required = false
            csv.required = false
            html.outputLocation = layout.buildDirectory.dir("jacocoHtml")
        }
        classDirectories.setFrom(
            files(classDirectories.files.map {
                fileTree(it) {
                    exclude("edu/missouristate/mars/venus/**")
                    exclude("edu/missouristate/mars/tools/**")
                }
            })
        )
    }

    compileJava {
        options.compilerArgs.addAll(listOf("-Xlint:unchecked"))
    }

    shadowJar {
        archiveBaseName.set("mars")
        archiveClassifier.set("")
        archiveVersion.set(project.version.toString())
        from(sourceSets.main.get().output)
        configurations {
            add(project.configurations.implementation.get())
        }
        from("src/main/resources") {
            into("")
        }
        manifest {
            attributes["Main-Class"] = "edu.missouristate.mars.Mars"
        }
    }

    withType<Copy>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        jvmToolchain(21)
    }
}