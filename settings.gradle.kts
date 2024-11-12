rootProject.name = "commons"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            // use the same libs.versions.toml as the rest of the project
            from(files("../gradle/libs.versions.toml"))
        }
    }
}