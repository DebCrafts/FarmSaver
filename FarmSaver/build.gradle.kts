plugins {
    java
}

group = "com.farmsaver"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Compile against the oldest supported API (1.20.6 = first Paper with Java 21).
    // The jar runs unchanged on newer Paper versions.
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
    processResources {
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    jar {
        archiveFileName.set("FarmSaver-${project.version}.jar")
    }
}
