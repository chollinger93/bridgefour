import Dependencies._

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
    name := "kaladin"
  )
  .dependsOn(shared)

lazy val worker = (project in file("modules/rock"))
  .settings(
    commonSettings,
    name := "rock"
  )
  .dependsOn(shared)

addCommandAlias("lint", ";scalafixAll --rules OrganizeImports")
addCommandAlias("format", ";scalafmtAll")
