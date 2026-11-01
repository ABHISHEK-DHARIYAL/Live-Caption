// Pure Kotlin/JVM module: text processing, audio maths, transcript model, export, scoring, model catalog.
// No Android dependency, so every test here runs on any JDK (and in CI) without an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application // `./gradlew :core:run --args="--dir <refs> <hyps>"` scores transcripts (WER/CER)
}

application { mainClass.set("com.clearcap.eval.EvalMainKt") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    testImplementation(libs.junit)
}
