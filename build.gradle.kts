
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.matching { it.name.startsWith("runServer") || it.name == "runObfServer" }.configureEach {
    (this as JavaExec).classpath = classpath.filter { !it.name.contains("angelica", ignoreCase = true) }
}

tasks.matching { it.name == "runServer" || it.name == "runObfServer" }.configureEach {
    (this as JavaExec).classpath = classpath.filter { !it.name.contains("lwjgl3ify", ignoreCase = true) }
}
