// The orders example (../orders) built with Gradle, to check the README's Gradle setup in CI. Install the SDK
// first: mvn install -DskipTests from the repository root puts 0.1.0-SNAPSHOT in ~/.m2.
plugins {
    java
}

repositories {
    mavenLocal()
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

val springBootVersion = providers.gradleProperty("springBootVersion").getOrElse("3.5.16")

// docuconf: start dependencies
dependencies {
    implementation(platform("dev.docuconf:docuconf-bom:0.1.0-SNAPSHOT"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    implementation("dev.docuconf:docuconf-spring")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    annotationProcessor(platform("dev.docuconf:docuconf-bom:0.1.0-SNAPSHOT"))
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    annotationProcessor("dev.docuconf:docuconf-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}
// docuconf: end dependencies

// The same sources as the Maven example.
sourceSets.main {
    java.setSrcDirs(listOf("../orders/src/main/java"))
    resources.setSrcDirs(listOf("../orders/src/main/resources"))
}

tasks.compileJava {
    options.compilerArgs.add("-Adocuconf.appVersion=1.0.0")
}

// docuconf: start contract-tasks
// application*.yml feeds the contract: make it an input of compileJava, and have the processor read it from the
// sources rather than from build/resources, which compileJava may see before processResources refreshes it.
val springResources = sourceSets.main.get().resources.sourceDirectories.filter { it.isDirectory }
tasks.compileJava {
    inputs.files(springResources.asFileTree.matching { include("application*.*", "config/application*.*") })
        .withPropertyName("docuconfSpringFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    options.compilerArgs.add("-Adocuconf.resources=${springResources.first()}")
}

val exportedContract = tasks.compileJava.flatMap { it.destinationDirectory.file("META-INF/docuconf/contract.cue") }

// ./gradlew docuconfExport writes contract.cue next to the build file, to commit.
val docuconfExport by tasks.registering {
    val exported = exportedContract
    val committed = layout.projectDirectory.file("contract.cue")
    inputs.file(exported)
    outputs.file(committed)
    doLast { exported.get().asFile.copyTo(committed.asFile, overwrite = true) }
}

// ./gradlew check fails when the committed contract.cue is not the exported one.
val docuconfCheck by tasks.registering {
    val exported = exportedContract
    val committed = layout.projectDirectory.file("contract.cue")
    inputs.file(exported)
    doLast {
        val now = exported.get().asFile.readText()
        if (!committed.asFile.exists() || committed.asFile.readText() != now) {
            throw GradleException("docuconf: contract.cue is out of date; run ./gradlew docuconfExport and commit it")
        }
    }
}
tasks.check { dependsOn(docuconfCheck) }
// docuconf: end contract-tasks
