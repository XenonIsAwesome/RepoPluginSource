import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Compile against the lowest supported version so we never use API missing there.
        clion("2026.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            // Deliberately no until-build: compatibility is decided by Plugin Verifier, not a hard-coded bound.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        // Fail on real breakage. Internal API usage is unavoidable (UpdateSettingsProvider and
        // PluginRepositoryAuthProvider are both @ApiStatus.Internal, see SPIKE.md) and confined to
        // adapter/InternalApiAdapter.kt, so it is reported in the verifier output but does not fail the build.
        // Experimental API usage is reported too; the plugin currently uses none.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        ides {
            // Dynamically select every CLion release/EAP/RC in range (261 and newer; no upper bound).
            select {
                types = listOf(IntelliJPlatformType.CLion)
                channels = listOf(ProductRelease.Channel.RELEASE, ProductRelease.Channel.EAP, ProductRelease.Channel.RC)
                sinceBuild = "261"
                untilBuild = provider { null }
            }
        }
    }
}
