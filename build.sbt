ThisBuild / organization := "dev.freskog"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.18"

val zioVersion     = "2.1.26"
val zioJsonVersion = "0.9.2"
val zioHttpVersion = "3.11.2"
val zioCliVersion  = "0.8.1"
val sqliteVersion  = "3.53.1.0"
val jlineVersion   = "3.27.1"

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
  .aggregate(common, safeRun, runlog, personService, runtime, mycroft, mycroftRepl)
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
      // Bake UTF-8 as the image charset. `sun.jnu.encoding` is what ProcessBuilder
      // uses to encode child-process arguments — without this it's baked from the
      // builder's POSIX locale (ASCII), mangling non-ASCII args (€, —, emoji) to `?`
      // when the agent spawns `person …`. Runtime LANG does not override the baked value.
      "-Dfile.encoding=UTF-8",
      "-Dsun.jnu.encoding=UTF-8",
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
      // Bake UTF-8 as the image charset. `sun.jnu.encoding` is what ProcessBuilder
      // uses to encode child-process arguments — without this it's baked from the
      // builder's POSIX locale (ASCII), mangling non-ASCII args (€, —, emoji) to `?`
      // when the agent spawns `person …`. Runtime LANG does not override the baked value.
      "-Dfile.encoding=UTF-8",
      "-Dsun.jnu.encoding=UTF-8",
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
      // Bake UTF-8 as the image charset. `sun.jnu.encoding` is what ProcessBuilder
      // uses to encode child-process arguments — without this it's baked from the
      // builder's POSIX locale (ASCII), mangling non-ASCII args (€, —, emoji) to `?`
      // when the agent spawns `person …`. Runtime LANG does not override the baked value.
      "-Dfile.encoding=UTF-8",
      "-Dsun.jnu.encoding=UTF-8",
      "--initialize-at-run-time=io.netty",
      "-H:+ReportExceptionStackTraces"
    )
  )

// person-cli was removed: the `person` verbs are now a thin curl/jq script
// (scripts/person) shipped directly into the runtime image. See docs/architecture.md.

lazy val mycroft = (project in file("modules/mycroft"))
  .dependsOn(common, safeRun, runtime)
  .enablePlugins(NativeImagePlugin)
  .settings(commonSettings)
  .settings(
    name := "mycroft",
    Compile / mainClass := Some("dev.freskog.agent.mycroft.Main"),
    libraryDependencies ++= Seq(
      "dev.zio"    %% "zio-http"     % zioHttpVersion,
      "dev.zio"    %% "zio-streams"  % zioVersion,
      "dev.zio"    %% "zio-json"     % zioJsonVersion,
      "dev.zio"    %% "zio-test"     % zioVersion % Test,
      "dev.zio"    %% "zio-test-sbt" % zioVersion % Test
    ),
    nativeImageOptions ++= Seq(
      "--no-fallback",
      // Bake UTF-8 as the image charset. `sun.jnu.encoding` is what ProcessBuilder
      // uses to encode child-process arguments — without this it's baked from the
      // builder's POSIX locale (ASCII), mangling non-ASCII args (€, —, emoji) to `?`
      // when the agent spawns `person …`. Runtime LANG does not override the baked value.
      "-Dfile.encoding=UTF-8",
      "-Dsun.jnu.encoding=UTF-8",
      "--initialize-at-run-time=io.netty",
      "-H:+ReportExceptionStackTraces"
    )
  )

// The REPL runs on a JVM (not native): it is started once and is not on the
// hot path, so JVM startup latency is irrelevant. Running on the JVM lets us
// use JLine for robust raw-mode line editing / bracketed paste. Packaged as a
// fat jar via sbt-assembly and shipped in a small JRE image.
lazy val mycroftRepl = (project in file("modules/mycroft-repl"))
  .dependsOn(common)
  .settings(commonSettings)
  .settings(
    name := "mycroft-repl",
    Compile / mainClass := Some("dev.freskog.agent.mycroft.repl.Main"),
    libraryDependencies ++= Seq(
      "dev.zio"  %% "zio"          % zioVersion,
      "dev.zio"  %% "zio-streams"  % zioVersion,
      "dev.zio"  %% "zio-json"     % zioJsonVersion,
      "org.jline" % "jline"        % jlineVersion,
      "dev.zio"  %% "zio-test"     % zioVersion % Test,
      "dev.zio"  %% "zio-test-sbt" % zioVersion % Test
    ),
    assembly / assemblyJarName := "mycroft-repl.jar",
    assembly / mainClass       := Some("dev.freskog.agent.mycroft.repl.Main"),
    assembly / assemblyMergeStrategy := {
      case p if p.endsWith("module-info.class")        => MergeStrategy.discard
      case PathList("META-INF", "services", _ @ _*)    => MergeStrategy.concat
      case PathList("META-INF", _ @ _*)                => MergeStrategy.discard
      case _                                           => MergeStrategy.first
    }
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
      // Bake UTF-8 as the image charset. `sun.jnu.encoding` is what ProcessBuilder
      // uses to encode child-process arguments — without this it's baked from the
      // builder's POSIX locale (ASCII), mangling non-ASCII args (€, —, emoji) to `?`
      // when the agent spawns `person …`. Runtime LANG does not override the baked value.
      "-Dfile.encoding=UTF-8",
      "-Dsun.jnu.encoding=UTF-8",
      "-H:+ReportExceptionStackTraces"
    )
  )
