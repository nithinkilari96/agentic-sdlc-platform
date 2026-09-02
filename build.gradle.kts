plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.kilari.agentic"
version = "0.1.0"

java {
    toolchain {
        // Pinned explicitly so the build does not depend on whatever JDK is on PATH.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Durable workflow state: the orchestrator must survive a process restart.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")

    // Audit-grade observability + the reliability metrics the brief names.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Official Anthropic SDK. Only the ClaudeModelProvider touches this; every
    // other layer depends on the ModelProvider interface, so the platform runs
    // end-to-end with no credentials and no vendor coupling.
    implementation("com.anthropic:anthropic-java:2.34.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
