plugins {
    java
}

allprojects {
    group = "org.phantazm"
    version = "1.0-SNAPSHOT"
    description = "Data for the Zombies minigame"
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven("https://dl.cloudsmith.io/public/steanky/ethylene/maven/")
        }
        filter {
            includeModuleByRegex("com\\.github\\.steanky", "ethylene-.+")
        }
    }
    exclusiveContent {
        forRepository {
            maven("https://dl.cloudsmith.io/public/steanky/vector/maven/")
        }
        filter {
            includeModule("com.github.steanky", "vector-core")
        }
    }
    exclusiveContent {
        forRepository {
            maven("https://dl.cloudsmith.io/public/steanky/toolkit/maven/")
        }
        filter {
            includeModuleByRegex("com\\.github\\.steanky", "toolkit-.+")
        }
    }
    exclusiveContent {
        forRepository {
            maven("https://dl.cloudsmith.io/public/steanky/element/maven/")
        }
        filter {
            includeModule("com.github.steanky", "element-core")
        }
    }
}

dependencies {
    implementation("com.github.steanky:element-core:0.18.2")
    implementation("com.github.steanky:ethylene-core:0.26.0")
    implementation("com.github.steanky:ethylene-mapper:0.23.0")
    implementation("com.github.steanky:vector-core:0.9.2")
    implementation("com.github.steanky:toolkit-collection:0.4.0")
    implementation("com.github.steanky:toolkit-function:0.4.0")
    implementation("net.kyori:adventure-api:4.11.0")
    implementation("net.kyori:adventure-text-minimessage:4.11.0")

    compileOnly("org.jetbrains:annotations:23.0.0")
    testCompileOnly("org.jetbrains:annotations:23.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}