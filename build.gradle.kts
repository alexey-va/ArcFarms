plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("io.github.drownek.plugwright") version "2.0.4"
    jacoco
}
group = "ru.ruscrafting"
version = "0.40.12"
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

val arcCoreVersion = "2.7.6"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-menu:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-redis:$arcCoreVersion")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:$arcCoreVersion")
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
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:$arcCoreVersion")
    testRuntimeOnly("me.clip:placeholderapi:2.12.3")
    testImplementation("net.luckperms:api:5.5")
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

// Real Paper coverage, with a one-bed order and a short mounted delivery route.
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
        file("plugins/ArcFarms/data/farm-routes.json", projectDir.resolve("src/test/e2e/fixtures/farm-routes.json"))
        file("plugins/ArcFarms/data/farm-locations.json", projectDir.resolve("src/test/e2e/fixtures/farm-locations.json"))
        file(
            "plugins/ArcFarms/config.yml",
            projectDir.resolve("src/main/resources/config.yml").readText()
                .replace("network:\n  enabled: true", "network:\n  enabled: false")
                .replace("menu-presentation: DIALOG", "menu-presentation: INVENTORY")
                .replace("default: ru", "default: en")
                .replace("use-client-locale: true", "use-client-locale: false")
                .replace("particles: true", "particles: false")
                .replace("debug:\n  enabled: false", "debug:\n  enabled: true")
                .replace("world: sp11", "world: world")
                .replace("preparation-patch-size: 100", "preparation-patch-size: 1")
                .replace("preparation-patch-max-size: 256", "preparation-patch-max-size: 1")
                .replace("field-completion-percent: 90", "field-completion-percent: 100")
                .replace("seeder-every-shifts: 2", "seeder-every-shifts: 0")
                .replace("rare-order-chance-percent: 20", "rare-order-chance-percent: 0")
                .replace(Regex("care-types: \\[[^\\n]+\\]"), "care-types: [WEEDS]")
                .replace("care-targets-per-player: 15", "care-targets-per-player: 1")
                .replace("care-targets-max: 45", "care-targets-max: 1")
                .replace(Regex("crops: \\[[^\\n]*:[^\\n]*\\]"), "crops: [WHEAT:1]")
                .replace("crates: 3", "crates: 1")
                .replace("x: 212.5, y: 49.0, z: 463.5", "x: 108.5, y: -60.0, z: 0.5")
                .replace(Regex("x: 212\\.5, y: 49\\.0, z: [0-9.]+"), "x: 112.5, y: -60.0, z: 10.5")
                .replace("x: 201.65\n      y: 49.0\n      z: 453.46", "x: 116.5\n      y: -60.0\n      z: 0.5")
                .replace("checkpoint-radius: 8.0", "checkpoint-radius: 2.0")
                .replace(
                    Regex("(?s)lumber-zones:.*?(?=mine-zones:)"),
                    projectDir.resolve("src/test/e2e/fixtures/lumber-zone.yml").readText(),
                )
                .replace(
                    Regex("(?ms)^mine-zones:.*?(?=^[a-z][a-z-]*:|\\z)"),
                    projectDir.resolve("src/test/e2e/fixtures/mine-zone.yml").readText(),
                )
                .replace("region: farm", "bounds:\n      min: [96, -64, -16]\n      max: [128, -40, 16]")
                .replace("region: spawn_lumbermill", "bounds:\n      min: [-8, 63, -8]\n      max: [8, 80, 8]")
                .replace("station-region: spawn_lumberhouse", "station-bounds:\n      min: [-8, 63, -8]\n      max: [8, 80, 8]")
                .replace("region: mine1", "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace("region: mine2", "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace("region: mine3", "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace("region: mine4", "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace(Regex("region: [^\\n]+"), "bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]")
                .replace(Regex("station-region: [^\\n]+"), "station-bounds:\n      min: [-8, 20, -8]\n      max: [8, 80, 8]"),
        )
        if (System.getenv("MINE_LIFT_TEST") == "true") {
            file("plugins/ArcFarms/modules/mine-lift.yml", projectDir.resolve("src/test/e2e/fixtures/mine-lift.yml"))
        }
    }
}
