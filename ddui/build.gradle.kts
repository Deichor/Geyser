plugins {
    id("geyser.publish-conventions")
}

dependencies {
    // The DDUI packets and their serializers already live in the Bedrock codec; this module only
    // models the screens and the datastore that drives them.
    api(libs.protocol.codec)

    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
