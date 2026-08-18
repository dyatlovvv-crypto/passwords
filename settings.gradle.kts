pluginManagement {
    repositories {
        maven { url = uri("${rootDir}/local-maven") }
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("${rootDir}/local-maven") }
        mavenLocal()
        google()
        mavenCentral()
    }
}
rootProject.name = "Passwords"
include(":app")
