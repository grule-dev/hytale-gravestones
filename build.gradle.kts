plugins {
    id("java")
    id("com.gradleup.shadow") version "9.3.0"
}

group = "com.github.grule"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly(files("server/Server/HytaleServer.jar"))
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        minimize()
        archiveClassifier.set("")
    }

    build {
        dependsOn(shadowJar)
    }

    register<Exec>("runServer") {
        group = "hytale"
        description = "Sets up and runs the Hytale server"

        dependsOn(build)

        val serverDir = file(".server")
        val modsDir = file(".server/mods")
        val assetsPath = project.findProperty("assetsPath")?.toString()
            ?: "server/Assets.zip"
        val modVersion = version.toString()

        doFirst {
            // Create directories
            serverDir.mkdirs()
            modsDir.mkdirs()

            // Copy server jar
            copy {
                from("server/Server/HytaleServer.jar")
                into(serverDir)
                rename { "server.jar" }
            }

            // Copy built mod jar to mods folder
            copy {
                from(file("build/libs").listFiles()?.filter { it.name.endsWith("$modVersion.jar") })
                into(modsDir)
            }
        }

        // Configure the exec task
        workingDir(serverDir)
        commandLine("java", "-jar", "server.jar", "--assets", file(assetsPath).absolutePath, "--bind", "0.0.0.0:5000")
        standardInput = System.`in`
    }
}