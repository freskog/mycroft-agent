package dev.freskog.agent.mycroft.tools

import dev.freskog.agent.common._
import dev.freskog.agent.saferun.{ProcessRunner, RunConfig, SafeRunError}

import zio._
import zio.json.ast.Json
import zio.json._

import java.nio.file.{Path, Paths}

/** The native OS tool surface mycroft advertises to the LLM. Deliberately tiny:
 *  `safe_run` executes bash through the in-process `safe-run` ProcessRunner
 *  (bounded output + logging), and `runlog` zooms into the full output a prior
 *  `safe_run` produced. Everything else — person, skill — is reached as a
 *  command through `safe_run`, discovered via skills. (A third control-plane
 *  tool, `run_skill`, is registered separately for skill composition.) */
final case class ToolOutcome(ok: Boolean, summary: String, content: String)

trait ToolRegistry {
  def dispatch(name: String, argsJson: String): IO[AgentError, ToolOutcome]
}

object ToolRegistry {

  /** The constant OS-tool `tools` array sent on every chat request. */
  val toolsJson: String =
    """[
      |  {
      |    "type": "function",
      |    "function": {
      |      "name": "safe_run",
      |      "description": "Run a bash command and get bounded output (a preview plus a runlog reference to the full log). Use this for the person CLI (durable memory/commitments/goals/events), the skill catalogue (skill list|search|show), and general unix. Discover usage with --help.",
      |      "parameters": {
      |        "type": "object",
      |        "properties": {
      |          "command": { "type": "string", "description": "The bash command to execute." },
      |          "timeout_seconds": { "type": "integer", "description": "Max seconds before the command is killed (default 30)." }
      |        },
      |        "required": ["command"]
      |      }
      |    }
      |  },
      |  {
      |    "type": "function",
      |    "function": {
      |      "name": "runlog",
      |      "description": "Zoom into the full output of an earlier safe_run when its preview was truncated. Pass runlog subcommand args, e.g. 'show <run_id> --stream stdout --tail 100', 'grep <run_id> --pattern <re>', or 'range <run_id> --start-line 40 --end-line 80'. Output is bounded.",
      |      "parameters": {
      |        "type": "object",
      |        "properties": {
      |          "args": { "type": "string", "description": "The runlog subcommand and flags, without the leading 'runlog'." }
      |        },
      |        "required": ["args"]
      |      }
      |    }
      |  }
      |]""".stripMargin

  private val MaxPreview = 4000

  def live(cwd: Path, defaultTimeout: Int): ToolRegistry = new ToolRegistry {

    def dispatch(name: String, argsJson: String): IO[AgentError, ToolOutcome] = name match {
      case "safe_run" => safeRun(argsJson)
      case "runlog"   => runlogTool(argsJson)
      case other =>
        ZIO.succeed(ToolOutcome(
          ok = false,
          summary = s"unknown tool '$other'",
          content = s"Error: no tool named '$other'. The OS tools are safe_run(command, timeout_seconds?) and runlog(args)."
        ))
    }

    private def safeRun(argsJson: String): IO[AgentError, ToolOutcome] = {
      val parsed = argsJson.fromJson[Json].toOption
      val command = parsed.flatMap(strField("command"))
      val timeout = parsed.flatMap(intField("timeout_seconds")).getOrElse(defaultTimeout)
      command match {
        case None | Some("") =>
          ZIO.succeed(ToolOutcome(false, "missing command", "Error: safe_run requires a non-empty 'command' string argument."))
        case Some(cmd) => runBounded(cmd, timeout)
      }
    }

    private def runlogTool(argsJson: String): IO[AgentError, ToolOutcome] = {
      val args = argsJson.fromJson[Json].toOption.flatMap(strField("args"))
      args match {
        case None | Some("") =>
          ZIO.succeed(ToolOutcome(false, "missing args", "Error: runlog requires a non-empty 'args' string, e.g. 'show <run_id> --tail 100'."))
        case Some(a) => runBounded(s"runlog $a", defaultTimeout)
      }
    }

    private def runBounded(cmd: String, timeout: Int): IO[AgentError, ToolOutcome] =
      ProcessRunner
        .run(RunConfig(cmd, Shell.Bash, cwd, timeout))
        .mapError(translate)
        .map(meta => summarise(cmd, meta))

    private def summarise(cmd: String, m: RunMetadata): ToolOutcome = {
      val exit = m.exitCode.map(_.toString).getOrElse(if (m.timedOut) "timeout" else "killed")
      val ok   = m.exitCode.contains(0)
      val out  = combinePreview(m.stdoutHead, m.stdoutTail, m.stdoutBytes)
      val err  = combinePreview(m.stderrHead, m.stderrTail, m.stderrBytes)
      val content = new StringBuilder
      content.append(s"$$ $cmd\n")
      content.append(s"exit: $exit (${m.durationMs} ms)\n")
      if (out.nonEmpty) content.append(s"stdout:\n$out\n")
      if (err.nonEmpty) content.append(s"stderr:\n$err\n")
      content.append(s"(full output: runlog ${m.stdoutLog})")
      ToolOutcome(ok, s"exit $exit, ${m.durationMs}ms, ${m.stdoutBytes}B stdout", truncate(content.toString))
    }

    private def combinePreview(head: String, tail: String, bytes: Long): String = {
      val h = head.trim
      val t = tail.trim
      if (h.isEmpty && t.isEmpty) ""
      else if (h == t || t.isEmpty) h
      else if (h.isEmpty) t
      else s"$h\n…\n$t"
    }

    private def truncate(s: String): String =
      if (s.length <= MaxPreview) s else s.take(MaxPreview) + "\n…(truncated)"

    private def translate(e: SafeRunError): AgentError = e match {
      case SafeRunError.InvalidCwd(p)          => AgentError.Validation(s"invalid working directory: $p")
      case SafeRunError.SetsidUnavailable      => AgentError.Bug("setsid not available in runtime image")
      case SafeRunError.UnsupportedShell(s)    => AgentError.Validation(s"unsupported shell: $s")
      case SafeRunError.IoError(m)             => AgentError.Persistence(s"shell io error: $m")
      case SafeRunError.ProcessStartFailed(m)  => AgentError.Persistence(s"failed to start process: $m")
    }

    private def strField(name: String)(json: Json): Option[String] = json match {
      case Json.Obj(fields) => fields.collectFirst { case (k, Json.Str(s)) if k == name => s }
      case _                => None
    }

    private def intField(name: String)(json: Json): Option[Int] = json match {
      case Json.Obj(fields) => fields.collectFirst { case (k, Json.Num(n)) if k == name => n.intValue }
      case _                => None
    }
  }

  def defaultCwd: Path =
    Paths.get(sys.env.getOrElse("MYCROFT_WORKDIR", sys.props.getOrElse("user.dir", ".")))
}
