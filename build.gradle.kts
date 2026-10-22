// Gradle build, kept equivalent to pom.xml: same dependencies, tests and CLI jar.
// Maven stays the build that publishes to Maven Central (see .github/workflows/release.yml).
plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
}

group = "io.github.danielonet"
// single source of truth for the version: the <version> right after <artifactId> in pom.xml
version = Regex("<artifactId>trafilatura-java</artifactId>\\s*<version>([^<]+)</version>")
    .find(file("pom.xml").readText())!!.groupValues[1]
description = "Java port of the Python trafilatura web text extraction library"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
}

val slf4jVersion = "2.0.16"
val lombokVersion = "1.18.36"

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.github.danielonet:justext:3.0.2")
    implementation("jaxen:jaxen:2.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    // backend for the CLI only; library users bring their own SLF4J binding
    runtimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")

    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.yaml:snakeyaml:2.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// license files go into META-INF of the jars, as Apache-2.0 section 4 requires
tasks.processResources {
    into("META-INF") {
        from("LICENSE", "NOTICE")
        into("licenses") { from("licenses") }
    }
}

tasks.test {
    useJUnitPlatform {
        // tests whose expectations need the full Python dependencies; run them with -PallTests
        if (!project.hasProperty("allTests")) excludeTags("known-deviation", "network")
    }
    filter { includeTestsMatching("*Tests") }
}

tasks.jar {
    manifest.attributes(
        "Main-Class" to "io.github.danielonet.trafilatura.Cli",
        "Implementation-Title" to project.name,
        "Implementation-Version" to project.version,
    )
}

// self-contained CLI jar: build/libs/trafilatura-java-<version>-cli.jar, run with java -jar
tasks.shadowJar {
    archiveClassifier = "cli"
    manifest.attributes(
        "Main-Class" to "io.github.danielonet.trafilatura.Cli",
        "Implementation-Title" to project.name,
        "Implementation-Version" to project.version,
        "Multi-Release" to "true",
    )
    // let service files from several jars reach the merge instead of keeping only the first
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    // BouncyCastle is a signed jar; its signatures are invalid once merged
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "module-info.class",
        "META-INF/versions/*/module-info.class")
}
