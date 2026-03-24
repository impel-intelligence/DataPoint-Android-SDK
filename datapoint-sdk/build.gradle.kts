import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.Properties
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    `maven-publish`
    signing
}

// Secrets from root local.properties (gitignored): signing.* and Sonatype deploy credentials
val localSecretsProperties =
    Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
fun shouldLoadFromLocalProperties(name: String): Boolean =
    name.startsWith("signing.") ||
        name == "maven.releases.username" ||
        name == "maven.releases.password"

for (key in localSecretsProperties.keys) {
    val name = key as String
    if (!shouldLoadFromLocalProperties(name)) continue
    var value = localSecretsProperties.getProperty(name)?.trim().orEmpty()
    if (value.isEmpty()) continue
    if (name == "signing.secretKeyRingFile") {
        val f = File(value)
        if (!f.isAbsolute) {
            value = rootProject.file(value).absolutePath
        }
    }
    extra.set(name, value)
}

/**
 * Prefer root `local.properties` for `signing.*` so values are not shadowed by
 * `~/.gradle/gradle.properties` or environment — a common reason the effective key/id
 * does not match what you expect from `local.properties`.
 */
private fun Project.signingProperty(name: String): String? {
    var v = localSecretsProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
    if (v == null) {
        v = (findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (name == "signing.secretKeyRingFile" && !v.isNullOrEmpty()) {
        val f = File(v)
        if (!f.isAbsolute) {
            v = rootProject.file(v).absolutePath
        }
    }
    return v
}

private fun looksLikePgpPrivateKeyArmored(material: String): Boolean =
    material.contains("BEGIN PGP PRIVATE KEY BLOCK", ignoreCase = true)

/** Resolves armored secret key from signing.key or signing.secretKey (armored text or single-line Base64). */
private fun Project.resolveInMemoryPgpSecretKey(): String? {
    val keyDirect =
        signingProperty("signing.key")
            ?.takeIf { it.isNotBlank() }
            ?.replace("\\n", "\n")
            ?.replace("\r\n", "\n")
    if (!keyDirect.isNullOrBlank() && looksLikePgpPrivateKeyArmored(keyDirect)) {
        return keyDirect.trim()
    }
    val raw = signingProperty("signing.secretKey")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val maybeArmored = raw.replace("\\n", "\n").replace("\r\n", "\n")
    if (looksLikePgpPrivateKeyArmored(maybeArmored)) {
        return maybeArmored.trim()
    }
    if (maybeArmored.contains("BEGIN PGP PUBLIC KEY BLOCK", ignoreCase = true)) {
        throw org.gradle.api.GradleException(
            "signing.secretKey / signing.key contains a PUBLIC key. Maven Central requires a secret key " +
                "(armored block must start with BEGIN PGP PRIVATE KEY BLOCK)."
        )
    }
    val cleaned = raw.replace(Regex("\\s+"), "")
    val decoded =
        runCatching { String(Base64.getDecoder().decode(cleaned), Charsets.UTF_8) }
            .recoverCatching { String(Base64.getMimeDecoder().decode(cleaned), Charsets.UTF_8) }
            .getOrElse { ex ->
                throw org.gradle.api.GradleException(
                    "Could not Base64-decode signing.secretKey (${ex.message}). " +
                        "Use a single line of standard Base64 (full key, not truncated), or paste the armored key in signing.key with \\\\n for newlines.",
                    ex
                )
            }
    val normalized = decoded.replace("\r\n", "\n").trim()
    if (!looksLikePgpPrivateKeyArmored(normalized)) {
        throw org.gradle.api.GradleException(
            "signing.secretKey must decode to an armored PGP secret key. Re-export with:\n" +
                "  gpg --armor --export-secret-keys <KEY_ID>\n" +
                "Then Base64-encode that output as one line, or put the armored block in signing.key."
        )
    }
    return normalized
}

private fun Project.normalizeSigningKeyId(): String? {
    val raw = signingProperty("signing.keyId") ?: return null
    val hex = raw.removePrefix("0x").removePrefix("0X")
    require(hex.isNotEmpty() && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        "signing.keyId must be hexadecimal (e.g. 8 or 16 hex chars for the signing subkey), was: $raw"
    }
    require(hex.length == 8 || hex.length == 16) {
        "signing.keyId should be the 8- or 16-character key id of the signing subkey (from gpg -K --with-subkey-fingerprints). Got length ${hex.length}."
    }
    return hex.uppercase()
}

android {
    namespace = "com.datapoint.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            // Stub Javadoc JAR: Dokka does not run reliably on JDK 25+; Maven still expects a -javadoc artifact.
        }
    }
}

// Minimal Javadoc JAR for Maven (API detail lives in -sources.jar)
val releaseJavadocStubDir = layout.buildDirectory.dir("javadoc-stub/release")
val prepareReleaseJavadocStub =
    tasks.register("prepareReleaseJavadocStub") {
        outputs.dir(releaseJavadocStubDir)
        doLast {
            val dir = releaseJavadocStubDir.get().asFile.apply { mkdirs() }
            File(dir, "index.html").writeText(
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>DataPoint SDK</title></head>" +
                    "<body><p>API documentation: use the <code>-sources.jar</code> artifact.</p></body></html>"
            )
        }
    }
val releaseJavadocJar =
    tasks.register<Jar>("releaseJavadocJar") {
        dependsOn(prepareReleaseJavadocStub)
        archiveClassifier.set("javadoc")
        from(releaseJavadocStubDir)
    }

val sdkGroupId: String = findProperty("sdk.groupId") as String? ?: "com.trydatapoint"
val sdkArtifactId: String = findProperty("sdk.artifactId") as String? ?: "sdk"
val sdkVersion: String = findProperty("sdk.version") as String? ?: "1.0.0"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(releaseJavadocJar)
                groupId = sdkGroupId
                artifactId = sdkArtifactId
                version = sdkVersion

                pom {
                    name.set(findProperty("sdk.pomName") as String? ?: sdkArtifactId)
                    description.set(
                        findProperty("sdk.pomDescription") as String? ?: "DataPoint Android SDK"
                    )
                    val pomUrl = findProperty("sdk.pomUrl") as String?
                    if (!pomUrl.isNullOrBlank()) url.set(pomUrl)

                    licenses {
                        license {
                            name.set(findProperty("sdk.pomLicenseName") as String? ?: "Proprietary")
                            val licenseUrl = findProperty("sdk.pomLicenseUrl") as String?
                            if (!licenseUrl.isNullOrBlank()) url.set(licenseUrl)
                        }
                    }
                    developers {
                        developer {
                            id.set(findProperty("sdk.pomDeveloperId") as String? ?: "datapoint")
                            name.set(findProperty("sdk.pomDeveloperName") as String? ?: "DataPoint")
                        }
                    }
                    val scmUrl = findProperty("sdk.pomScmUrl") as String?
                    if (!scmUrl.isNullOrBlank()) {
                        scm {
                            url.set(scmUrl)
                            val conn = findProperty("sdk.pomScmConnection") as String?
                            val devConn = findProperty("sdk.pomScmDevConnection") as String?
                            if (!conn.isNullOrBlank()) connection.set(conn)
                            if (!devConn.isNullOrBlank()) developerConnection.set(devConn)
                        }
                    }
                }
            }
        }

        val releasesUrl = findProperty("maven.releases.url") as String?
        if (!releasesUrl.isNullOrBlank()) {
            repositories {
                maven {
                    name = "releases"
                    url = uri(releasesUrl.trimEnd('/'))
                    val user = findProperty("maven.releases.username") as String?
                    val pass = findProperty("maven.releases.password") as String?
                    if (!user.isNullOrEmpty() && pass != null) {
                        credentials {
                            username = user
                            password = pass
                        }
                    }
                }
            }
        }
    }

    extensions.configure<SigningExtension>("signing") {
        val inMemoryKey = project.resolveInMemoryPgpSecretKey()
        val inMemoryKeyId = project.normalizeSigningKeyId()
        val inMemoryPassword = project.signingProperty("signing.password")
        val secretKeyRingFile = project.signingProperty("signing.secretKeyRingFile")

        val canSignInMemory =
            !inMemoryKey.isNullOrBlank() &&
                looksLikePgpPrivateKeyArmored(inMemoryKey) &&
                !inMemoryKeyId.isNullOrBlank() &&
                !inMemoryPassword.isNullOrBlank()
        val ringPath = secretKeyRingFile?.takeIf { it.isNotBlank() }
        val canSignFromFile =
            ringPath != null &&
                file(ringPath).isFile &&
                !inMemoryPassword.isNullOrBlank()
        val hasSigningMaterial = canSignInMemory || canSignFromFile
        val signingEnabledRaw = project.signingProperty("signing.enabled")
        val mavenSigningEnabled =
            when (signingEnabledRaw) {
                null, "" -> hasSigningMaterial
                else ->
                    signingEnabledRaw.toBooleanStrictOrNull()
                        ?: throw org.gradle.api.GradleException(
                            "Invalid signing.enabled value \"$signingEnabledRaw\" (use true/false)"
                        )
            }

        val publication = publishing.publications["release"]
        when {
            mavenSigningEnabled && canSignInMemory -> {
                useInMemoryPgpKeys(inMemoryKeyId, inMemoryKey, inMemoryPassword)
                sign(publication)
            }
            mavenSigningEnabled && canSignFromFile -> {
                sign(publication)
            }
        }
    }

    // Gradle maven-publish only sends PUTs; Central Portal needs this POST from the same IP as the upload.
    // https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/
    val stagingApiHost = "ossrh-staging-api.central.sonatype.com"
    val releasesUrlForFinalize = findProperty("maven.releases.url") as String?
    val skipFinalize = (findProperty("maven.central.skipFinalizePortalUpload") as String?) == "true"
    if (
        !skipFinalize &&
            !releasesUrlForFinalize.isNullOrBlank() &&
            releasesUrlForFinalize.contains(stagingApiHost)
    ) {
        val finalizeCentralPortalDeployment =
            tasks.register("finalizeCentralPortalDeployment") {
                group = "publishing"
                description =
                    "POSTs to Sonatype OSSRH Staging API so the deployment appears in " +
                        "https://central.sonatype.com/publishing — required after maven-publish PUTs."

                doLast {
                    val user = findProperty("maven.releases.username") as String?
                    val pass = findProperty("maven.releases.password") as String?
                    require(!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                        "maven.releases.username and maven.releases.password must be set (e.g. in local.properties)"
                    }
                    val namespace =
                        (findProperty("maven.central.namespace") as String?)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: (findProperty("sdk.groupId") as String?)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: sdkGroupId
                    val publishingType =
                        (findProperty("maven.central.publishingType") as String?)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: "user_managed"
                    require(
                        publishingType == "user_managed" ||
                            publishingType == "automatic" ||
                            publishingType == "portal_api"
                    ) {
                        "maven.central.publishingType must be user_managed, automatic, or portal_api (got \"$publishingType\")"
                    }

                    val bearer =
                        Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
                    val uri =
                        URI.create(
                            "https://$stagingApiHost/manual/upload/defaultRepository/" +
                                namespace.trim('/').replace(" ", "%20") +
                                "?publishing_type=" +
                                publishingType
                        )
                    val client = HttpClient.newBuilder().build()
                    val request =
                        HttpRequest.newBuilder(uri)
                            .header("Authorization", "Bearer $bearer")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !in 200..299) {
                        throw org.gradle.api.GradleException(
                            "Central Portal finalize failed: HTTP ${response.statusCode()} ${response.body()}"
                        )
                    }
                    logger.lifecycle(
                        "Central Portal: deployment handed off for namespace \"$namespace\" " +
                            "(check https://central.sonatype.com/publishing/deployments)."
                    )
                }
            }

        tasks.named("publishReleasePublicationToReleasesRepository").configure {
            doFirst {
                val inMemoryKey = project.resolveInMemoryPgpSecretKey()
                val inMemoryKeyId = project.normalizeSigningKeyId()
                val inMemoryPassword = project.signingProperty("signing.password")
                val secretKeyRingFile = project.signingProperty("signing.secretKeyRingFile")
                val canSignInMemory =
                    !inMemoryKey.isNullOrBlank() &&
                        looksLikePgpPrivateKeyArmored(inMemoryKey) &&
                        !inMemoryKeyId.isNullOrBlank() &&
                        !inMemoryPassword.isNullOrBlank()
                val ringPath = secretKeyRingFile?.takeIf { it.isNotBlank() }
                val canSignFromFile =
                    ringPath != null &&
                        file(ringPath).isFile &&
                        !inMemoryPassword.isNullOrBlank()
                check(canSignInMemory || canSignFromFile) {
                    "Maven Central upload requires signed artifacts. Configure signing.keyId/signing.password and signing.key (or signing.secretKey, or signing.secretKeyRingFile)."
                }
            }
            finalizedBy(finalizeCentralPortalDeployment)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.ads.identifier)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
