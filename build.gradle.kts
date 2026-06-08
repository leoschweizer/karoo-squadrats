plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
