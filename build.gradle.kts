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
    kotlin("jvm") version "1.9.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "edu.missouristate"
version = "2.0-SNAPSHOT"

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
    sourceCompatibility = JavaVersion.VERSION_20
    targetCompatibility = JavaVersion.VERSION_20
}

tasks {
    test {
        useJUnitPlatform()
    }

    compileJava {
        options.compilerArgs.addAll(listOf("--enable-preview", "-Xlint:unchecked"))
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
            into("edu/missouristate/mars")
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
            jvmTarget.set(JvmTarget.JVM_20)
        }

        jvmToolchain(20)
    }
}