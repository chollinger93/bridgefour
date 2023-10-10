import sbt.*

object Dependencies {

  // Versions
  val CatsEffectVersion              = "3.5.0"
  val PureConfigVersion              = "0.17.4"
  val Http4sVersion                  = "0.23.23"
  val MunitVersion                   = "0.7.29"
  val LogbackVersion                 = "1.4.7"
  val MunitCatsEffectVersion         = "1.0.7"
  val CirceVersion                   = "0.14.5"
  val Log4CatsVersion                = "2.6.0"
  val CirceADTVersion                = "0.11.0"
  val ScalafixOrganizeImportsVersion = "0.6.0"

  lazy val sharedDeps: Seq[ModuleID] = Seq(
    "org.typelevel" %% "cats-effect"        % CatsEffectVersion,
    "org.typelevel" %% "cats-effect-kernel" % CatsEffectVersion,
    "org.typelevel" %% "cats-effect-std"    % CatsEffectVersion,
    // Pureconfig
    "com.github.pureconfig" %% "pureconfig-core"        % PureConfigVersion,
    "com.github.pureconfig" %% "pureconfig-cats"        % PureConfigVersion,
    "com.github.pureconfig" %% "pureconfig-cats-effect" % PureConfigVersion,
    // Http4s
    "org.http4s" %% "http4s-ember-client" % Http4sVersion,
    "org.http4s" %% "http4s-ember-server" % Http4sVersion,
    "org.http4s" %% "http4s-dsl"          % Http4sVersion,
    "org.http4s" %% "http4s-circe"        % Http4sVersion,
    // Logs
    "ch.qos.logback" % "logback-classic" % LogbackVersion,
    "org.typelevel" %% "log4cats-slf4j"  % Log4CatsVersion,
    // Coding
    "io.circe"       % "circe-core_3"           % CirceVersion,
    "io.circe"      %% "circe-generic"          % CirceVersion,
    "io.circe"      %% "circe-parser"           % CirceVersion,
    "io.circe"      %% "circe-jawn"             % CirceVersion,
    "io.circe"      %% "circe-literal"          % CirceVersion,
    "org.latestbit" %% "circe-tagged-adt-codec" % CirceADTVersion,
    // Tests
    "org.typelevel" %% "cats-effect-testing-specs2" % "1.4.0"                % Test,
    "org.typelevel" %% "munit-cats-effect-3"        % "1.0.7"                % Test,
    "org.scalameta" %% "munit"                      % MunitVersion           % Test,
    "org.typelevel" %% "munit-cats-effect-3"        % MunitCatsEffectVersion % Test
  )

}
