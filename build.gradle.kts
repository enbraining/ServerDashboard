plugins {
    java
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
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    jar {
        enabled = false
    }

    register<Jar>("shadowJar") {
        group = "build"
        description = "Assembles a fat JAR including runtime dependencies."
        archiveBaseName.set("ServerDashboard")
        archiveVersion.set(project.version.toString())
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        // 컴파일된 클래스 + 리소스
        from(sourceSets.main.get().output)
        // 런타임 의존성(Gson 등) 포함
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })

        // Gson 패키지 재배치 (ManifestTransformer 없이 직접 수행 불필요 — Paper는 자체 Gson 보유)
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    build {
        dependsOn(named("shadowJar"))
    }
}
