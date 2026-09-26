rootProject.name = "boss-plugin-terminal-tab"

// Paired development against a matching local BossTerm checkout.
providers.gradleProperty("bosstermSourceDir").orNull?.let { sourceDir ->
    includeBuild(sourceDir) {
        dependencySubstitution {
            substitute(module("com.risaboss:bossterm-compose")).using(project(":compose-ui"))
            substitute(module("com.risaboss:bossterm-compose-desktop")).using(project(":compose-ui"))
            substitute(module("com.risaboss:bossterm-core-jvm")).using(project(":bossterm-core-mpp"))
        }
    }
}
