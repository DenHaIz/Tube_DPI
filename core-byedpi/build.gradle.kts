import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tube.byedpi"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        unitTests.all { test ->
            // Проверки через настоящий интернет — только по запросу: gradlew :core-byedpi:testDebugUnitTest -Pnetwork
            if (project.hasProperty("network")) test.systemProperty("network", "true")
            test.testLogging { showStandardStreams = project.hasProperty("network") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * ndk-build исполняемого byedpi и переименование в lib<abi>/libbyedpi.so: так установщик
 * распакует его в nativeLibraryDir, откуда Android разрешает запуск.
 */
abstract class BuildByeDpi @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    abstract val sources: DirectoryProperty

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val jniLibs: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @TaskAction
    fun build() {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val ndkBuild = ndkDirectory.file(if (windows) "ndk-build.cmd" else "ndk-build").get().asFile
        val work = workDir.get().asFile
        exec.exec {
            commandLine(
                ndkBuild.absolutePath,
                "NDK_PROJECT_PATH=null",
                "APP_BUILD_SCRIPT=${sources.file("Android.mk").get().asFile.absolutePath}",
                "NDK_APPLICATION_MK=${sources.file("Application.mk").get().asFile.absolutePath}",
                "NDK_OUT=${File(work, "obj").absolutePath}",
                "NDK_LIBS_OUT=${File(work, "libs").absolutePath}",
                "-j${Runtime.getRuntime().availableProcessors()}",
            )
        }
        val out = jniLibs.get().asFile
        out.deleteRecursively()
        abis.get().forEach { abi ->
            File(work, "libs/$abi/byedpi").copyTo(File(out, "$abi/libbyedpi.so"), overwrite = true)
        }
    }
}

val buildByeDpi = tasks.register<BuildByeDpi>("buildByeDpi") {
    sources.set(layout.projectDirectory.dir("src/main/cpp"))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    abis.set(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
    workDir.set(layout.buildDirectory.dir("byedpi"))
    jniLibs.set(layout.buildDirectory.dir("byedpi/jniLibs"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildByeDpi, BuildByeDpi::jniLibs)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
