/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    java
    idea
    kotlin("jvm") version "2.1.0"
    id("com.gradleup.shadow") version "8.3.5"
    jacoco
}

group = "edu.missouristate"
version = "4.6-SNAPSHOT"

val mockitoAgent = configurations.create("mockitoAgent")

repositories {
    mavenCentral()
}

dependencies {
    // Java
    compileOnly("org.jetbrains:annotations:26.0.1")
    // Kotlin
    implementation(kotlin("stdlib-jdk8"))
    // Testing
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
    mockitoAgent("org.mockito:mockito-core:5.14.2") { isTransitive = false }
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
        jvmArgs("-XX:+EnableDynamicAgentLoading", "-javaagent:${mockitoAgent.asPath}")
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