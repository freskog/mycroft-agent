package dev.freskog.agent.saferun

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.cli._
import zio.json._

import java.nio.file.{Path, Paths}

object Main extends ZIOCliDefault {

  val cwdOption = Options.directory("cwd")
    .map(p => Paths.get(p.toUri))

  val timeoutOption = Options.integer("timeout")
    .withDefault(BigInt(30))
    .map(_.toInt)

  val shellOption = Options.text("shell")
    .withDefault("bash")

  val commandArg = Args.text("command")

  val safeRunCommand = Command("safe-run", cwdOption ++ timeoutOption ++ shellOption, commandArg)

  val cliApp = CliApp.make(
    name = "safe-run",
    version = "0.1.0",
    summary = HelpDoc.Span.text("Safe command execution wrapper for AI agents"),
    command = safeRunCommand
  ) { case ((cwd, timeout, shellStr), command) =>
    Shell.fromString(shellStr) match {
      case Left(err) =>
        JsonOutput.stderr(err) *> ZIO.succeed(())
      case Right(shell) =>
        val config = RunConfig(
          command = command,
          shell = shell,
          cwd = cwd,
          timeoutSeconds = timeout
        )
        ProcessRunner.run(config).foldZIO(
          {
            case SafeRunError.InvalidCwd(path) =>
              JsonOutput.stderr(s"Invalid working directory: $path")
            case SafeRunError.SetsidUnavailable =>
              JsonOutput.stderr("setsid not found. Install util-linux.")
            case SafeRunError.UnsupportedShell(s) =>
              JsonOutput.stderr(s"Unsupported shell: $s")
            case SafeRunError.IoError(msg) =>
              JsonOutput.stderr(s"IO error: $msg")
            case SafeRunError.ProcessStartFailed(msg) =>
              JsonOutput.stderr(s"Failed to start process: $msg")
          },
          meta => JsonOutput.ok(meta)
        )
    }
  }
}
