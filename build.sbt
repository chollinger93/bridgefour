import Dependencies.*
import com.typesafe.sbt.packager.docker.ExecCmd

ThisBuild / organization := "com.chollinger"
ThisBuild / scalaVersion := "3.3.0"
ThisBuild / name         := "bridgefour"

// Scalafix
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % ScalafixOrganizeImportsVersion

lazy val commonSettings = Seq(
  libraryDependencies   ++= sharedDeps,
  semanticdbEnabled      := true,
  semanticdbIncludeInJar := true
)

lazy val root = Project(id = "bridgefour", base = file("."))
  .aggregate(leader, worker)

lazy val shared = (project in file("modules/shared")).settings(
  commonSettings,
  name := "shared"
)

lazy val leader = (project in file("modules/kaladin"))
  .settings(
    commonSettings,
    name := "kaladin",
    dockerUpdateLatest := true,
    dockerBaseImage := "eclipse-temurin:17-jdk",
  )
  .dependsOn(shared)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val worker = (project in file("modules/spren"))
  .settings(
    commonSettings,
    name := "spren",
    dockerUpdateLatest := true,
    dockerBaseImage := "eclipse-temurin:17-jdk"
  )
  .dependsOn(shared)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

addCommandAlias("lint", ";scalafixAll --rules OrganizeImports")
addCommandAlias("format", ";scalafmtAll")
// Plugins
enablePlugins(JavaAppPackaging, DockerPlugin)
