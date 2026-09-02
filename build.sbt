import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import sbtbuildinfo.BuildInfoKey

lazy val versions = new {
  val catsCore    = "2.13.0"
  val catsEffect  = "3.5.7"
  val circe       = "0.14.15"
  val circeConfig = "0.10.2"
  val pureConfig  = "0.17.10"
  val fs2         = "3.13.0"
  val fs2Kafka    = "3.5.1"
  val scalaTest   = "3.2.19"
  val http4s      = "0.23.34"
  val logging     = "2.7.1"
  val slf4j       = "2.0.18"

  // Java
  // Confluent versions newer than these pin a non-existent jetty-bom version
  // (9.4.59 / 9.4.61, missing the .vYYYYMMDD qualifier) in their parent POM,
  // which breaks dependency resolution entirely. Pinned to the last versions
  // with a valid jetty-bom reference.
  val schemaRegistry       = "7.7.6"
  val jsonSchemaSerializer = "7.4.12"
  val jsonSchemaValidator  = "1.0.88"

  val specs2              = "4.20.9"
  val catsEffectTesting   = "1.8.0"
  val testcontainers      = "1.20.6"
  val testcontainersScala = "0.41.8"
}

lazy val deps = new {
  val catsCore          = "org.typelevel"         %% "cats-core"           % versions.catsCore
  val catsEffect        = "org.typelevel"         %% "cats-effect"         % versions.catsEffect
  val circeCore         = "io.circe"              %% "circe-core"          % versions.circe
  val circeGeneric      = "io.circe"              %% "circe-generic"       % versions.circe
  val circeParser       = "io.circe"              %% "circe-parser"        % versions.circe
  val circeConfig       = "io.circe"              %% "circe-config"        % versions.circeConfig
  val pureConfig        = "com.github.pureconfig" %% "pureconfig"          % versions.pureConfig
  val fs2               = "co.fs2"                %% "fs2-core"            % versions.fs2
  val fs2io             = "co.fs2"                %% "fs2-io"              % versions.fs2
  val fs2kafka          = "com.github.fd4s"       %% "fs2-kafka"           % versions.fs2Kafka
  val http4sEmberClient = "org.http4s"            %% "http4s-ember-client" % versions.http4s
  val http4sEmberServer = "org.http4s"            %% "http4s-ember-server" % versions.http4s
  val http4sDsl         = "org.http4s"            %% "http4s-dsl"          % versions.http4s
  val http4sCirce       = "org.http4s"            %% "http4s-circe"        % versions.http4s
  val logging           = "org.typelevel"         %% "log4cats-slf4j"      % versions.logging
  val slf4j             = "org.slf4j"              % "slf4j-simple"        % versions.slf4j

  // Java
  val schemaRegistry       = "io.confluent"  % "kafka-schema-registry-client" % versions.schemaRegistry
  val jsonSchemaSerializer = "io.confluent"  % "kafka-json-schema-serializer" % versions.jsonSchemaSerializer
  val jsonSchemaValidator  = "com.networknt" % "json-schema-validator"        % versions.jsonSchemaValidator

  val specs2              = "org.specs2"       %% "specs2-core"                % versions.specs2            % Test
  val catsEffectTesting   = "org.typelevel"    %% "cats-effect-testing-specs2" % versions.catsEffectTesting % Test
  val testcontainers      = "org.testcontainers" % "testcontainers"            % versions.testcontainers    % Test
  val testcontainersScala = "com.dimafeng"     %% "testcontainers-scala-core"  % versions.testcontainersScala % Test
}

lazy val commonSettings = Seq(
  scalaVersion := "2.13.18",
  resolvers += "Confluent" at "https://packages.confluent.io/maven/",
  Compile / mainClass := Some("valistrio.Main"),
  Global  / lintUnusedKeysOnLoad := false,
  libraryDependencies ++= Seq(
    deps.catsCore,
    deps.catsEffect,
    deps.circeCore,
    deps.circeGeneric,
    deps.circeParser,
    deps.circeConfig,
    deps.pureConfig,
    deps.fs2,
    deps.fs2io,
    deps.fs2kafka,
    deps.http4sCirce,
    deps.http4sDsl,
    deps.http4sEmberClient,
    deps.http4sEmberServer,
    deps.logging,
    deps.slf4j,
    deps.schemaRegistry,
    deps.jsonSchemaSerializer,
    deps.jsonSchemaValidator,
    deps.specs2
  )
)

// Fixed tag used by both the Docker build and ValistrioContainer
val DockerImageTag = "it"

val javaVersion = IO.read(file(".java-version")).trim

lazy val app = project
  .in(file("modules/app"))
  .enablePlugins(sbt.plugins.JvmPlugin, JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(
    commonSettings,
    // Docker
    Docker / packageName    := "valistrio",
    Docker / version        := DockerImageTag,
    dockerBaseImage         := s"eclipse-temurin:$javaVersion-jre-jammy",
    dockerExposedPorts      := Seq(8080),
    dockerUpdateLatest      := false,
    // BuildInfo — exposes image coordinates to the IT module
    buildInfoPackage        := "valistrio",
    buildInfoKeys           := Seq[BuildInfoKey](
      BuildInfoKey.action("dockerImageName") { "valistrio" },
      BuildInfoKey.action("dockerImageTag")  { DockerImageTag }
    )
  )

lazy val it = project
  .in(file("modules/it"))
  .dependsOn(app % "compile->compile;test->test")
  .settings(
    scalaVersion                  := "2.13.18",
    resolvers                     += "Confluent" at "https://packages.confluent.io/maven/",
    publish / skip                := true,
    Global / lintUnusedKeysOnLoad := false,
    Test / fork                   := true,
    // On macOS, Docker Desktop 29+ has minimum API version 1.40 but docker-java
    // (bundled with Testcontainers) defaults to v1.26, causing 400 responses.
    // We fix this with a JVM system property (docker-java reads api.version) and
    // an env var so Testcontainers itself also sees the right version.
    Test / javaOptions            ++= Seq(
      "-Dapi.version=1.41",
      "-DDOCKER_API_VERSION=1.41",
      s"-DDOCKER_HOST=unix:///var/run/docker.sock"
    ),
    Test / envVars                ++= {
      val host = sys.env.getOrElse("DOCKER_HOST", "unix:///var/run/docker.sock")
      Map(
        "DOCKER_HOST"                          -> host,
        "DOCKER_API_VERSION"                   -> "1.41",
        "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE" -> "/var/run/docker.sock"
      )
    },
    Test / test     := (Test / test).dependsOn(app / Docker / publishLocal).value,
    Test / testOnly := (Test / testOnly).dependsOn(app / Docker / publishLocal).evaluated,
    libraryDependencies ++= Seq(
      deps.catsEffect,
      deps.catsEffectTesting,
      deps.testcontainers,
      deps.testcontainersScala,
      deps.fs2kafka,
      deps.http4sEmberClient,
      deps.logging,
      deps.slf4j,
      deps.specs2
    )
  )
