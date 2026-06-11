package dev.freskog.agent.saferun

import dev.freskog.agent.common._

import zio._

import java.nio.file.{Files, Path}
import java.util.UUID
import scala.jdk.CollectionConverters._

case class RunConfig(
  command: String,
  shell: Shell,
  cwd: Path,
  timeoutSeconds: Int,
  // Extra environment variables for the child process (merged over the inherited
  // env). Used to pass per-turn context (e.g. the conversation channel) down to
  // the `person` CLI without the agent having to thread it by hand.
  env: Map[String, String] = Map.empty
)

object ProcessRunner {

  def run(config: RunConfig): ZIO[Any, SafeRunError, RunMetadata] =
    for {
      _       <- validateCwd(config.cwd)
      _       <- checkSetsid
      runId   <- ZIO.succeed(UUID.randomUUID().toString)
      runsDir <- ensureRunsDir(config.cwd)
      result  <- execute(config, runId, runsDir)
    } yield result

  private def validateCwd(cwd: Path): IO[SafeRunError, Unit] =
    ZIO.attemptBlocking(Files.isDirectory(cwd))
      .mapError(e => SafeRunError.IoError(e.getMessage))
      .flatMap {
        case true  => ZIO.unit
        case false => ZIO.fail(SafeRunError.InvalidCwd(cwd.toString))
      }

  private def checkSetsid: IO[SafeRunError, Unit] =
    ZIO.attemptBlocking {
      val pb = new ProcessBuilder("which", "setsid")
      pb.redirectErrorStream(true)
      val p = pb.start()
      p.waitFor() == 0
    }.mapError(e => SafeRunError.IoError(e.getMessage)).flatMap {
      case true  => ZIO.unit
      case false => ZIO.fail(SafeRunError.SetsidUnavailable)
    }

  private def ensureRunsDir(cwd: Path): IO[SafeRunError, Path] =
    ZIO.attemptBlocking {
      val dir = cwd.resolve(".agent").resolve("runs")
      Files.createDirectories(dir)
      dir
    }.mapError(e => SafeRunError.IoError(s"Cannot create runs directory: ${e.getMessage}"))

  private def execute(config: RunConfig, runId: String, runsDir: Path): ZIO[Any, SafeRunError, RunMetadata] = {
    val stdoutFile = runsDir.resolve(s"$runId.stdout")
    val stderrFile = runsDir.resolve(s"$runId.stderr")
    val metaFile   = runsDir.resolve(s"$runId.json")

    val shellCmd = Shell.toCommand(config.shell)
    val cmd      = List("setsid", shellCmd, "-lc", config.command).asJava

    for {
      startedAt <- Clock.instant
      process   <- ZIO.attemptBlocking {
        val pb = new ProcessBuilder(cmd)
        pb.directory(config.cwd.toFile)
        if (config.env.nonEmpty) pb.environment().putAll(config.env.asJava)
        pb.redirectOutput(stdoutFile.toFile)
        pb.redirectError(stderrFile.toFile)
        pb.start()
      }.mapError(e => SafeRunError.ProcessStartFailed(e.getMessage))

      exitResult <- awaitWithTimeout(process, config.timeoutSeconds)
      finishedAt <- Clock.instant

      stdoutBytes <- fileSize(stdoutFile)
      stderrBytes <- fileSize(stderrFile)
      stdoutHead  <- Preview.extractHead(stdoutFile).orElseSucceed("")
      stdoutTail  <- Preview.extractTail(stdoutFile).orElseSucceed("")
      stderrHead  <- Preview.extractHead(stderrFile).orElseSucceed("")
      stderrTail  <- Preview.extractTail(stderrFile).orElseSucceed("")

      durationMs = java.time.Duration.between(startedAt, finishedAt).toMillis

      meta = RunMetadata(
        runId = runId,
        command = config.command,
        shell = shellCmd,
        cwd = config.cwd.toString,
        startedAt = startedAt,
        finishedAt = finishedAt,
        durationMs = durationMs,
        exitCode = exitResult.exitCode,
        timedOut = exitResult.timedOut,
        termination = exitResult.termination,
        stdoutBytes = stdoutBytes,
        stderrBytes = stderrBytes,
        stdoutHead = stdoutHead,
        stdoutTail = stdoutTail,
        stderrHead = stderrHead,
        stderrTail = stderrTail,
        stdoutLog = stdoutFile.toString,
        stderrLog = stderrFile.toString,
        metadataLog = metaFile.toString
      )

      _ <- writeMetadata(metaFile, meta)
    } yield meta
  }

  private case class ExitResult(
    exitCode: Option[Int],
    timedOut: Boolean,
    termination: Option[String]
  )

  private def awaitWithTimeout(process: Process, timeoutSeconds: Int): ZIO[Any, SafeRunError, ExitResult] = {
    val await: ZIO[Any, SafeRunError, ExitResult] = ZIO.attemptBlocking {
      val code = process.waitFor()
      ExitResult(Some(code), timedOut = false, termination = None)
    }.mapError(e => SafeRunError.IoError(e.getMessage))

    val timeout: ZIO[Any, SafeRunError, ExitResult] =
      ZIO.sleep(Duration.fromSeconds(timeoutSeconds.toLong)) *>
        gracefulKill(process).as(ExitResult(None, timedOut = true, termination = Some("timeout")))

    await.race(timeout)
  }

  private def gracefulKill(process: Process): ZIO[Any, Nothing, Unit] = {
    val pid = process.pid()

    val killTerm = ZIO.attemptBlocking {
      java.lang.Runtime.getRuntime.exec(Array("kill", "-TERM", s"-$pid"))
      ()
    }.ignore

    val waitGrace = ZIO.attemptBlocking {
      process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
    }.orElseSucceed(false)

    val killForce = ZIO.attemptBlocking {
      java.lang.Runtime.getRuntime.exec(Array("kill", "-KILL", s"-$pid"))
      process.waitFor()
      ()
    }.ignore

    killTerm *> waitGrace.flatMap {
      case true  => ZIO.unit
      case false => killForce
    }
  }

  private def fileSize(path: Path): ZIO[Any, SafeRunError, Long] =
    ZIO.attemptBlocking(Files.size(path))
      .orElseSucceed(0L)

  private def writeMetadata(path: Path, meta: RunMetadata): IO[SafeRunError, Unit] = {
    import dev.freskog.agent.common.JsonCodecs._
    import zio.json._
    ZIO.attemptBlocking {
      Files.writeString(path, meta.toJson)
      ()
    }.mapError(e => SafeRunError.IoError(s"Cannot write metadata: ${e.getMessage}"))
  }
}

sealed trait SafeRunError
object SafeRunError {
  case class InvalidCwd(path: String)            extends SafeRunError
  case object SetsidUnavailable                  extends SafeRunError
  case class UnsupportedShell(shell: String)      extends SafeRunError
  case class IoError(message: String)            extends SafeRunError
  case class ProcessStartFailed(message: String) extends SafeRunError
}
