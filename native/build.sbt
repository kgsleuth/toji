ThisBuild / scalaVersion := "3.4.0"
ThisBuild / organization := "io.toji"
ThisBuild / version := "0.1.1"

lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "toji",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % "4.0.2",
      "org.scalameta" %%% "munit" % "1.0.0" % Test,
    ),
    Compile / mainClass := Some("io.toji.Main"),
  )

addCommandAlias("tojiLink", "nativeLink")
addCommandAlias("tojiReleaseFast", "nativeLinkReleaseFast")
addCommandAlias("tojiRun", "run")
