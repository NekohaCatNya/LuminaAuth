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
        // 内网/证书问题无法直接拉取 Maven 的依赖，手动 curl 落到这里，优先命中
        maven { url = uri("${rootDir}/libs-maven") }
        mavenLocal()
        google()
        mavenCentral()
    }
}
rootProject.name = "SchoolAutologinCompose"
include(":app")
