pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

val localProps = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext from Github Packages
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = localProps.getProperty("gpr.user") ?: System.getenv("USERNAME") ?: ""
                password = localProps.getProperty("gpr.key") ?: System.getenv("TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "karoo-squadrats"
include("app")
