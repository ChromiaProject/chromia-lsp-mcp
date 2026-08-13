plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
    application
}

group = "com.chromia"

// The git tag is the version: the release jobs only push a tag, and the deploy job passes it in
// as -Pversion. Local builds get the placeholder from gradle.properties.
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()

    maven("https://gitlab.com/api/v4/projects/32802097/packages/maven") {
        name = "rell"
        // Only the language server's fat JAR comes from here, and its POM's parent chain lives in
        // registries this project has no reason to declare. Take the artifact, skip the metadata.
        metadataSources { artifact() }
    }

    maven("https://gitlab.com/api/v4/projects/32294340/packages/maven") {
        name = "postchain"
    }
}

/**
 * The Rell language server itself. It is not a compile dependency — it runs as a separate process
 * — so it lives in its own configuration and is only ever copied into the image.
 */
val rellLsp: Configuration = configurations.create("rellLsp") { isTransitive = false }

dependencies {
    implementation(libs.mcp.server)
    implementation(libs.lsp4j)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.kotlinx.io.core)
    runtimeOnly(libs.slf4j.simple)

    // Artifact-only (`@jar`): the fat JAR is the whole point, and letting Gradle parse its POM
    // instead drags in the postchain dependency chain and its parents, which live in further
    // registries and are none of this project's business.
    rellLsp(libs.rell.lsp.get().let { "${it.module}:${it.versionConstraint.requiredVersion}:all@jar" })

    testImplementation(libs.mcp.client)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin.jvmToolchain(21)

application.mainClass = "com.chromia.lspmcp.MainKt"

tasks.processResources {
    // Without this the task stays up to date across a version change and stamps the old one.
    inputs.property("version", project.version)
    filesMatching("build-info.properties") {
        expand("version" to project.version)
    }
}

/** Stages the language server JAR under the name the image and the tests expect. */
val stageRellLsp = tasks.register<Sync>("stageRellLsp") {
    from(rellLsp) { rename { "language-server.jar" } }
    into(layout.buildDirectory.dir("rell-lsp"))
}

val stagedRellLspJar: Provider<String> =
    layout.buildDirectory.file("rell-lsp/language-server.jar").map { it.asFile.absolutePath }

tasks.test {
    useJUnitPlatform()
    dependsOn(stageRellLsp)
    // The integration tests need a language server. Default to the one that goes into the image;
    // RELL_LSP_JAR overrides it when testing against a different version.
    environment(
        "RELL_LSP_JAR",
        providers.environmentVariable("RELL_LSP_JAR").orElse(stagedRellLspJar).get(),
    )
    testLogging {
        events("passed", "skipped", "failed")
        // -PtestLogs surfaces the server's own stderr, which is where its debug log goes.
        showStandardStreams = providers.gradleProperty("testLogs").isPresent
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Both architectures by default; a local `jibBuildTar` has to narrow this to one, since a tar
// holds a single image rather than a manifest list.
val imagePlatforms = providers.gradleProperty("imagePlatforms").getOrElse("amd64,arm64").split(",")

// Reproducible image metadata: the same source produces the same image digest. Outside a git
// checkout this falls back to the epoch, which is Jib's own default.
val epoch = "1970-01-01T00:00:00Z"
val sourceDate: Provider<String> = if (rootProject.file(".git").exists()) {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%cI")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { epoch } }
} else {
    providers.provider { epoch }
}

jib {
    from {
        // The organisation's Java 21 base, pinned by digest the way chromia-cli pins it.
        image = "registry.gitlab.com/chromaway/core-tools/chromia-images/java21:1.0.19@" +
                "sha256:badf799acda31620ce99ea80c12e398ca9392691167c6f821d4c4fd744297d0e"

        // Built without emulation: Jib assembles each manifest from the multi-arch base image.
        platforms {
            for (architecture in imagePlatforms) {
                platform {
                    this.architecture = architecture.trim()
                    os = "linux"
                }
            }
        }
    }
    to {
        image = providers.gradleProperty("image")
            .orElse(providers.environmentVariable("CI_REGISTRY_IMAGE"))
            .getOrElse("registry.gitlab.com/chromaway/core-tools/chromia-lsp-mcp")

        tags = providers.gradleProperty("imageTags")
            .map { it.split(",").map(String::trim).toSet() }
            .getOrElse(setOf(version.toString()))

        auth {
            username = providers.environmentVariable("CI_REGISTRY_USER").orNull
            password = providers.environmentVariable("CI_REGISTRY_PASSWORD").orNull
        }
    }
    container {
        mainClass = application.mainClass.get()
        // The server talks MCP over stdio, so it must own stdout and never buffer it.
        jvmFlags = listOf("-XX:+UseSerialGC", "-Xss4m")
        environment = mapOf(
            "RELL_LSP_JAR" to "/opt/rell-lsp/language-server.jar",
            // The language server caches its project index under HOME; /tmp is writable whatever
            // --user the caller passes.
            "HOME" to "/tmp",
        )
        labels = mapOf(
            "org.opencontainers.image.title" to "chromia-lsp-mcp",
            "org.opencontainers.image.description" to "Rell language intelligence as an MCP server",
            "org.opencontainers.image.source" to "https://gitlab.com/chromaway/core-tools/chromia-lsp-mcp",
            "org.opencontainers.image.licenses" to "MIT",
            "com.chromia.rell-lsp.version" to libs.versions.rell.lsp.get(),
        )
        // Docker V2.2, not OCI: Jib can only assemble a Docker manifest list for a
        // multi-platform build, and fails with "Build an OCI image index is not yet
        // supported" at the manifest step if the format is OCI.
        creationTime = sourceDate.get()
        filesModificationTime = sourceDate.get()
    }
    extraDirectories {
        paths {
            path {
                setFrom(layout.buildDirectory.dir("rell-lsp"))
                into = "/opt/rell-lsp"
            }
        }
    }
}

for (task in listOf("jib", "jibDockerBuild", "jibBuildTar")) {
    tasks.named(task) { dependsOn(stageRellLsp) }
}
