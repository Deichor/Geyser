plugins {
    id("geyser.platform-conventions")
    id("geyser.modrinth-uploading-conventions")
    alias(libs.plugins.runvelocity)
}

dependencies {
    annotationProcessor(libs.velocity.api)
    api(projects.core)

    compileOnly(libs.velocity.proxy)
    compileOnly(libs.netty.transport.native.io.uring)
    compileOnly(libs.netty.transport.native.kqueue)

    compileOnlyApi(libs.velocity.api)
    api(libs.cloud.velocity)

    // The pack this proxy composes and serves. Shaded, because nothing else on the proxy has it.
    //
    // 9.3.0 is not optional once any backend is on Carbon 9.3.0: the contribution format went to 2
    // there, and a decoder that does not know a version refuses the payload outright rather than
    // reading half of it. So this proxy is deployed *before* the shards that announce, or their
    // contributions are dropped and the pack composes without them — silently, from the client's
    // point of view.
    implementation("net.cubizor.carbon:carbon-bedrock-ui:9.3.2")

    // How a backend's contribution reaches this proxy. Provided by the ProxyBridge plugin, which is
    // where the transport actually lives — this only needs the message types.
    compileOnly("net.cubizor.proxybridge:api:4.2.0")

    // Provided by the Titan Velocity fork, which ships the Kotlin runtime in the proxy jar and does
    // not relocate it. Shading a second copy here would be 1.7MB of duplicate classes.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")

    // The pack sync's guards are ordinary logic and are tested as such; Kotlin is on the test
    // classpath because what it drives is a Kotlin API.
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    // compileOnly in main because the proxy provides them; a test has no proxy to provide them.
    testImplementation("net.cubizor.proxybridge:api:4.2.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

platformRelocate("it.unimi.dsi.fastutil")
platformRelocate("org.yaml")
platformRelocate("org.spongepowered")
platformRelocate("org.bstats")
platformRelocate("org.incendo")
platformRelocate("io.leangen.geantyref") // provided by cloud and Configurate, should also be relocated
        
// These dependencies are already present on the platform
provided(libs.velocity.api)

tasks.withType<Jar> {
    manifest.attributes["Main-Class"] = "org.geysermc.geyser.platform.velocity.GeyserVelocityMain"
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("Geyser-Velocity")

    dependencies {
        exclude(dependency("com.google.*:.*"))
        exclude(dependency("io.netty:.*"))
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("org.ow2.asm:.*"))
        // Exclude all Kyori dependencies
        exclude(dependency("net.kyori:.*:.*"))
        // Both are on the proxy already: Kotlin comes with the Titan Velocity fork, and the
        // ProxyBridge plugin brings its own API.
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("net.cubizor.proxybridge:.*"))
    }
}

modrinth {
    uploadFile.set(tasks.getByPath("shadowJar"))
    loaders.addAll("velocity")
}

tasks {
    runVelocity {
        version(libs.versions.runvelocityversion.get())
    }
}
