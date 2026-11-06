pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        // 内网/证书问题无法直接拉取 Maven Central 的依赖，手动 curl 落到这里
        maven { url = uri("${rootDir}/libs-maven") }
    }
}
rootProject.name = "SchoolAutologinCompose"
include(":app")
