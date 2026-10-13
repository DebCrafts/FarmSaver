plugins {
    java
}

group = "com.farmsaver"
version = "2.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Compile against the oldest supported API (1.20.6 = first Paper with Java 21).
    // The jar runs unchanged on newer Paper versions.
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    // AuraSkills API (Maven Central). Only used for XpGainEvent; AuraSkills stays a soft dependency.
    compileOnly("dev.aurelium:auraskills-api-bukkit:2.2.4")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    test { useJUnitPlatform() }
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
