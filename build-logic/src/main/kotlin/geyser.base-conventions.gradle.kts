plugins {
    `java-library`
    id("net.kyori.indra")
}

val rootProperties: Map<String, *> = project.rootProject.properties
group = rootProperties["group"] as String + "." + rootProperties["id"] as String
version = rootProperties["version"] as String
description = rootProperties["description"] as String

indra {
    github("GeyserMC", "Geyser") {
        ci(true)
        issues(true)
        scm(true)
    }
    mitLicense()

    javaVersions {
        target(21)
    }
}

dependencies {
    compileOnly("org.checkerframework:checker-qual:" + libs.checker.qual.get().version)
}

repositories {
    // Only when a local build is explicitly asked for, which is what keeps the warning below true.
    // A change that spans Carbon, ProxyBridge and this fork cannot be compiled at all until the
    // first two are released, so there has to be a way to point at a `publishToMavenLocal` — but it
    // is a way somebody has to type, on the command line, per build. Nothing is picked up by being
    // in ~/.m2. @see bootstrap/velocity/build.gradle.kts
    if (providers.gradleProperty("localCarbon").isPresent ||
        providers.gradleProperty("localProxyBridge").isPresent
    ) {
        mavenLocal()
    }

    // The pack sync links against carbon-bedrock-ui and proxybridge's api, both published here.
    // Credentials are read the same way every Titan repo reads them, so one setting covers all of
    // them. mavenLocal is deliberately off unless asked for above: it was on unconditionally while
    // those two were unpublished, and a local artefact silently winning over a released one is
    // exactly the kind of difference between a developer's build and CI that nobody notices until
    // a deploy.
    maven {
        name = "TitanPackages"
        url = uri("https://maven.pkg.github.com/titan-minecraft/*")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("TITAN_PKG_READ_TOKEN")
        }
    }

    mavenCentral()

    // Floodgate, Cumulus etc.
    maven("https://repo.opencollab.dev/main")

    // Paper, Velocity
    maven("https://repo.papermc.io/repository/maven-public")

    // Spigot
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots") {
        mavenContent { snapshotsOnly() }
    }

    // NeoForge
    maven("https://maven.neoforged.net/releases") {
        mavenContent { releasesOnly() }
    }

    // Minecraft
    maven("https://libraries.minecraft.net") {
        name = "minecraft"
        mavenContent { releasesOnly() }
    }

    // ViaVersion
    maven("https://repo.viaversion.com") {
        name = "viaversion"
    }

    // Jitpack for e.g. MCPL
    maven("https://jitpack.io") {
        content { includeGroupByRegex("com\\.github\\..*") }
    }
}
