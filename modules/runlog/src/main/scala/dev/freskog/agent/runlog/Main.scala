package dev.freskog.agent.runlog

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.cli._
import zio.json._

import java.nio.file.{Path, Paths}

object Main extends ZIOCliDefault {

  // `--cwd` is optional: when omitted we look under the current working
  // directory's `.agent/runs`. safe_run and runlog share the same process cwd,
  // so the default resolves to the right run store without the caller needing
  // to thread `--cwd` through every invocation.
  val cwdOption = Options
    .directory("cwd")
    .map(p => Paths.get(p.toUri))
    .withDefault(Paths.get(sys.props.getOrElse("user.dir", ".")))
  val streamOption = Options.text("stream").withDefault("stdout")
  val headOption = Options.integer("head").optional.map(_.map(_.toInt))
  val tailOption = Options.integer("tail").optional.map(_.map(_.toInt))
  val patternOption = Options.text("pattern")
  val startLineOption = Options.integer("start-line").map(_.toInt)
  val endLineOption = Options.integer("end-line").map(_.toInt)
  val runIdArg = Args.text("run-id")

  val listCommand = Command("list", cwdOption)
  val showCommand = Command("show", cwdOption ++ streamOption ++ headOption ++ tailOption, runIdArg)
  val grepCommand = Command("grep", cwdOption ++ streamOption ++ patternOption, runIdArg)
  val rangeCommand = Command("range", cwdOption ++ streamOption ++ startLineOption ++ endLineOption, runIdArg)

  sealed trait Subcommand
  case class ListCmd(cwd: Path) extends Subcommand
  case class ShowCmd(cwd: Path, stream: String, headLines: Option[Int], tailLines: Option[Int], runId: String) extends Subcommand
  case class GrepCmd(cwd: Path, stream: String, pattern: String, runId: String) extends Subcommand
  case class RangeCmd(cwd: Path, stream: String, startLine: Int, endLine: Int, runId: String) extends Subcommand

  val runlogCommand = Command("runlog").subcommands(
    listCommand.map { cwd => ListCmd(cwd) },
    showCommand.map { case ((cwd, stream, headOpt, tailOpt), runId) => ShowCmd(cwd, stream, headOpt, tailOpt, runId) },
    grepCommand.map { case ((cwd, stream, pattern), runId) => GrepCmd(cwd, stream, pattern, runId) },
    rangeCommand.map { case ((cwd, stream, start, end), runId) => RangeCmd(cwd, stream, start, end, runId) }
  )

  val cliApp = CliApp.make(
    name = "runlog",
    version = "0.1.0",
    summary = HelpDoc.Span.text("Log inspection CLI for safe-run outputs"),
    command = runlogCommand
  ) { cmd =>
    handleCommand(cmd).catchAll { err =>
      val msg = err match {
        case RunlogError.InvalidRunId(id)     => s"Invalid run ID: $id"
        case RunlogError.InvalidStream(s)     => s"Invalid stream: $s (use stdout or stderr)"
        case RunlogError.NotFound(detail)     => detail
        case RunlogError.IoError(message)     => s"IO error: $message"
      }
      JsonOutput.error(msg)
    }
  }

  private def handleCommand(cmd: Subcommand): ZIO[Any, RunlogError, Unit] = cmd match {
    case ListCmd(cwd) =>
      for {
        runs <- LogReader.list(cwd)
        summaries = runs.map(r => Map(
          "runId" -> r.runId,
          "command" -> r.command,
          "startedAt" -> r.startedAt.toString,
          "exitCode" -> r.exitCode.map(_.toString).getOrElse("null"),
          "timedOut" -> r.timedOut.toString
        ))
        _ <- JsonOutput.ok(summaries)
      } yield ()

    case ShowCmd(cwd, stream, headOpt, tailOpt, runId) =>
      val lines = headOpt.orElse(tailOpt).getOrElse(100)
      val result = if (headOpt.isDefined) LogReader.head(cwd, runId, stream, lines)
                   else if (tailOpt.isDefined) LogReader.tail(cwd, runId, stream, lines)
                   else LogReader.head(cwd, runId, stream, lines)
      result.flatMap(content => JsonOutput.ok(Map("runId" -> runId, "stream" -> stream, "content" -> content)))

    case GrepCmd(cwd, stream, pattern, runId) =>
      for {
        matches <- LogReader.grep(cwd, runId, stream, pattern)
        _ <- JsonOutput.ok(Map("runId" -> runId, "stream" -> stream, "pattern" -> pattern, "matches" -> matches.mkString("\n")))
      } yield ()

    case RangeCmd(cwd, stream, start, end, runId) =>
      for {
        content <- LogReader.range(cwd, runId, stream, start, end)
        _ <- JsonOutput.ok(Map("runId" -> runId, "stream" -> stream, "startLine" -> start.toString, "endLine" -> end.toString, "content" -> content))
      } yield ()
  }
}
