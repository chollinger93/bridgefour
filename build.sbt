import Dependencies.*
import com.typesafe.sbt.packager.docker.ExecCmd

ThisBuild / organization := "com.chollinger"
ThisBuild / scalaVersion := "3.6.4"
ThisBuild / name         := "bridgefour"

// Scalafix
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// Build
ThisBuild / usePipelining := true

// Compiler
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",      // Warn about deprecated APIs
  "-feature",          // Warn about feature usage
  "-new-syntax:false", // Prevents rewriting braces to indentation syntax
  "-Wunused:all",       // Warn about unused imports
  "-source:3.4-migration", "-rewrite"
)

lazy val commonSettings = Seq(
  libraryDependencies   ++= sharedDeps,
  semanticdbEnabled      := true,
  semanticdbIncludeInJar := true
)

lazy val root = Project(id = "bridgefour", base = file("."))
  .aggregate(shared, leader, worker)

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


addCommandAlias("lint", "scalafixAll")
addCommandAlias("fmt", "scalafmtAll")
addCommandAlias("testAll", "testQuick")

// Plugins
enablePlugins(JavaAppPackaging, DockerPlugin)
