plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("io.github.drownek.plugwright") version "2.0.4"
    jacoco
}
group = "ru.ruscrafting"
version = "0.35.1"
description = "Shared farm, lumbermill, and mine activities for RusCrafting"

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") { content { includeGroup("ru.ruscrafting.arc") } }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

val arcCoreVersion = "2.7.4"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-menu:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-redis:$arcCoreVersion")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.16")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.18")
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1") {
        exclude(group = "io.netty")
    }
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:$arcCoreVersion")
    testRuntimeOnly("me.clip:placeholderapi:2.12.3")
    testRuntimeOnly("net.luckperms:api:5.5")
    testRuntimeOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:$arcCoreVersion")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
}

tasks {
    withType<Test>().configureEach {
        // MockK/ByteBuddy must attach inside the forked JVM on JDK 25. Without this,
        // the external helper can hang and leave an orphan Gradle Test Executor.
        jvmArgs("-Djdk.attach.allowAttachSelf=true")
    }
    processResources {
        inputs.property("pluginVersion", project.version)
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test {
        useJUnitPlatform()
        // The full MockBukkit suite constructs several complete 64x64 worlds.
        // Keep the test worker above the JVM default so late suites do not fail
        // with unrelated coroutine-debugging OOMs after hundreds of green tests.
        maxHeapSize = "1g"
        systemProperty("arcfarms.projectDir", projectDir.absolutePath)
        providers.gradleProperty("ruscraftingOpsRoot")
            .orElse(providers.environmentVariable("RUSCRAFTING_OPS_ROOT"))
            .orNull
            ?.let { systemProperty("ruscrafting.opsRoot", it) }
    }
    register<Test>("integrationTest") {
        description = "Runs the disposable cross-node Redis integration tests."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(test)
    }
    jar { archiveClassifier.set("plain") }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("org/slf4j/**")
        exclude("com/sk89q/**")
        exclude("org/bukkit/**")
        exclude("io/papermc/**")
    }
    check { dependsOn(shadowJar, "integrationTest") }
}

// Real Paper coverage for the player-facing worksite entry journey.
plugwright {
    minecraftVersion.set("1.21.11")
    runDir.set(layout.buildDirectory.dir("plugwright"))
    testsDir.set(layout.projectDirectory.dir("src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    writeFiles {
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file(
            "plugins/ArcFarms/config.yml",
            projectDir.resolve("src/main/resources/config.yml").readText()
                .replace("network:\n  enabled: true", "network:\n  enabled: false")
                .replace("menu-presentation: DIALOG", "menu-presentation: INVENTORY")
                .replace("world: sp11", "world: world")
                .replace("farm-zones:\n  communal_farm:\n    enabled: true", "farm-zones:\n  communal_farm:\n    enabled: false")
                .replace("lumber-zones:\n  communal_lumbermill:\n    enabled: true", "lumber-zones:\n  communal_lumbermill:\n    enabled: false")
                .replace("mine-zones:\n  old_shafts:\n    enabled: true", "mine-zones:\n  old_shafts:\n    enabled: false")
                .replace("region: farm", "bounds:\n      min: [-8, 63, -8]\n      max: [8, 80, 8]")
                .replace("region: spawn_lumbermill", "bounds:\n      min: [-8, 63, -8]\n      max: [8, 80, 8]")
                .replace("station-region: spawn_lumberhouse", "station-bounds:\n      min: [-8, 63, -8]\n      max: [8, 80, 8]")
                .replace("region: mine1", "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace("region: mine2", "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace("region: mine3", "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace("region: mine4", "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace(Regex("region: [^\\n]+"), "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace(Regex("station-region: [^\\n]+"), "station-bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]"),
        )
    }
}
