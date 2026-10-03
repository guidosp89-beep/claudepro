// JVM-only CLIs that run the cloud experiments and write results/cloud/*.
plugins { application }
dependencies {
    implementation(project(":generators"))
}
application { mainClass.set("ghostlink.lab.tools.MainKt") }
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    maxHeapSize = "3g"
}
