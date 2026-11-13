
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.main.get().compileClasspath
}

tasks.matching { it.name.startsWith("runServer") || it.name == "runObfServer" }.configureEach {
    (this as JavaExec).classpath = classpath.filter { !it.name.contains("angelica", ignoreCase = true) }
}

tasks.matching { it.name == "runServer" || it.name == "runObfServer" }.configureEach {
    (this as JavaExec).classpath = classpath.filter { !it.name.contains("lwjgl3ify", ignoreCase = true) }
}
