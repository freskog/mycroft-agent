ThisBuild / organization := "dev.freskog"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.18"

val zioVersion     = "2.1.26"
val zioJsonVersion = "0.9.2"
val zioHttpVersion = "3.11.2"
val zioCliVersion  = "0.8.1"
val sqliteVersion  = "3.53.1.0"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "utf-8",
    "-feature",
    "-unchecked"
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

lazy val root = (project in file("."))
  .aggregate(common, safeRun, runlog, personCli, personService, runtime)
  .settings(
    name := "personal-agent",
    publish / skip := true
  )

lazy val common = (project in file("modules/common"))
  .settings(commonSettings)
  .settings(
    name := "common",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-streams"  % zioVersion,
      "dev.zio" %% "zio-json"     % zioJsonVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    )
  )

lazy val safeRun = (project in file("modules/safe-run"))
  .dependsOn(common)
  .enablePlugins(NativeImagePlugin)
  .settings(commonSettings)
  .settings(
    name := "safe-run",
    Compile / mainClass := Some("dev.freskog.agent.saferun.Main"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-cli"      % zioCliVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    nativeImageOptions ++= Seq(
      "--no-fallback",
      "-H:+ReportExceptionStackTraces"
    )
  )

lazy val runlog = (project in file("modules/runlog"))
  .dependsOn(common)
  .enablePlugins(NativeImagePlugin)
  .settings(commonSettings)
  .settings(
    name := "runlog",
    Compile / mainClass := Some("dev.freskog.agent.runlog.Main"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-cli"      % zioCliVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    nativeImageOptions ++= Seq(
      "--no-fallback",
      "-H:+ReportExceptionStackTraces"
    )
  )

lazy val personService = (project in file("modules/person-service"))
  .dependsOn(common)
  .enablePlugins(NativeImagePlugin)
  .settings(commonSettings)
  .settings(
    name := "person-service",
    Compile / mainClass := Some("dev.freskog.agent.person.Main"),
    libraryDependencies ++= Seq(
      "dev.zio"    %% "zio-http"     % zioHttpVersion,
      "org.xerial"  % "sqlite-jdbc"  % sqliteVersion,
      "dev.zio"    %% "zio-test"     % zioVersion % Test,
      "dev.zio"    %% "zio-test-sbt" % zioVersion % Test
    ),
    nativeImageOptions ++= Seq(
      "--no-fallback",
      "--initialize-at-run-time=io.netty",
      "-H:+ReportExceptionStackTraces"
    )
  )

lazy val personCli = (project in file("modules/person-cli"))
  .dependsOn(common)
  .enablePlugins(NativeImagePlugin)
  .settings(commonSettings)
  .settings(
    name := "person-cli",
    Compile / mainClass := Some("dev.freskog.agent.person.cli.Main"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-cli"      % zioCliVersion,
      "dev.zio" %% "zio-json"     % zioJsonVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    nativeImageOptions ++= Seq(
      "--no-fallback",
      "-H:+ReportExceptionStackTraces"
    )
  )

lazy val runtime = (project in file("modules/runtime"))
  .dependsOn(common)
  .enablePlugins(NativeImagePlugin)
  .settings(commonSettings)
  .settings(
    name := "runtime",
    Compile / mainClass := Some("dev.freskog.agent.runtime.Main"),
    libraryDependencies ++= Seq(
      "dev.zio"    %% "zio-cli"      % zioCliVersion,
      "org.xerial"  % "sqlite-jdbc"  % sqliteVersion,
      "dev.zio"    %% "zio-test"     % zioVersion % Test,
      "dev.zio"    %% "zio-test-sbt" % zioVersion % Test
    ),
    nativeImageOptions ++= Seq(
      "--no-fallback",
      "-H:+ReportExceptionStackTraces"
    )
  )
