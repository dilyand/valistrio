lazy val versions = new {
  val catsCore    = "2.10.0"
  val catsEffect  = "3.5.4"
  val circe       = "0.14.9"
  val circeConfig = "0.9.0"
  val pureConfig  = "0.17.2"
  val fs2         = "3.12.2"
  val scalaTest   = "3.2.19"
  val http4s      = "0.23.33"
  val logging     = "2.7.0"
  val slf4j       = "2.0.16"
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
  val http4sEmberClient = "org.http4s"            %% "http4s-ember-client" % versions.http4s
  val http4sEmberServer = "org.http4s"            %% "http4s-ember-server" % versions.http4s
  val http4sDsl         = "org.http4s"            %% "http4s-dsl"          % versions.http4s
  val http4sCirce       = "org.http4s"            %% "http4s-circe"        % versions.http4s
  val logging           = "org.typelevel"         %% "log4cats-slf4j"      % versions.logging
  val slf4j             = "org.slf4j"              % "slf4j-simple"        % versions.slf4j

  val scalaTest = "org.scalatest" %% "scalatest" % versions.scalaTest % Test
}

lazy val commonSettings = Seq(
  scalaVersion := "2.13.16",
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
    deps.http4sCirce,
    deps.http4sDsl,
    deps.http4sEmberClient,
    deps.http4sEmberServer,
    deps.logging,
    deps.slf4j,
    deps.scalaTest
  )
)

lazy val app = project.in(file("modules/app")).enablePlugins(sbt.plugins.JvmPlugin).settings(commonSettings)
