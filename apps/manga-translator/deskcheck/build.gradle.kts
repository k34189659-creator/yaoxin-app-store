plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(kotlin("stdlib"))
}

val copyTypeset by tasks.registering(Copy::class) {
    from("../engine/src/main/kotlin/li/joye/yakuyomi/engine/typeset/Typesetter.kt")
    into(layout.buildDirectory.dir("gen/typeset"))
}

sourceSets {
    main {
        kotlin.srcDir(layout.buildDirectory.dir("gen/typeset"))
    }
}

tasks.named("compileKotlin") { dependsOn(copyTypeset) }

tasks.register<JavaExec>("runCheck") {
    group = "verification"
    description = "Render before/after typesetting samples with the same layout engine as the app"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("DesktopCheckKt")
    jvmArgs("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8")
    args = listOf(
        rootProject.file("..").resolve("testpages").absolutePath,
        rootProject.file("build/deskcheck").absolutePath,
    )
}
