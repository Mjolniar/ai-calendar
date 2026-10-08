// Standalone Gradle build: headless UI tests and screenshots for the phone app.
// It is not part of the app's own build. See README.md in this folder.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ai-calendar-ui-harness"
