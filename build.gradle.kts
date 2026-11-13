
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// Angelica's SDL GPU backend compiles shaders at runtime and refuses to start without these bindings and their
// natives. RFG adds them to the lwjgl3 runClient tasks; the plain runClient is lwjgl2 and can't run SDL GPU at all.
configure<MinecraftExtension> {
    lwjgl3Bindings.addAll("shaderc", "spvc")
}

tasks.matching { it.name.startsWith("runServer") || it.name == "runObfServer" }.configureEach {
    (this as JavaExec).classpath = classpath.filter { !it.name.contains("angelica", ignoreCase = true) }
}

tasks.matching { it.name == "runServer" || it.name == "runObfServer" }.configureEach {
    (this as JavaExec).classpath = classpath.filter { !it.name.contains("lwjgl3ify", ignoreCase = true) }
}
