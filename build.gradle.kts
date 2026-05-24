plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.serverdashboard"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks {
    shadowJar {
        relocate("com.google.gson", "com.serverdashboard.libs.gson")
        archiveClassifier.set("")
        archiveBaseName.set("ServerDashboard")
    }
    build {
        dependsOn(shadowJar)
    }
    jar {
        enabled = false
    }
    compileJava {
        options.encoding = "UTF-8"
    }
}
