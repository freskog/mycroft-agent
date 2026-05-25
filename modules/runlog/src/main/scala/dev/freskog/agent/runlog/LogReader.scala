package dev.freskog.agent.runlog

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.json._

import java.nio.file.{Files, Path}
import java.util.regex.Pattern
import scala.jdk.CollectionConverters._

sealed trait RunlogError
object RunlogError {
  case class InvalidRunId(id: String)     extends RunlogError
  case class InvalidStream(stream: String) extends RunlogError
  case class NotFound(detail: String)     extends RunlogError
  case class IoError(message: String)     extends RunlogError
}

object LogReader {

  private val uuidPattern = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

  def validateRunId(id: String): Either[RunlogError, String] =
    if (uuidPattern.matcher(id).matches()) Right(id)
    else Left(RunlogError.InvalidRunId(id))

  def validateStream(stream: String): Either[RunlogError, String] =
    stream.toLowerCase match {
      case "stdout" | "stderr" => Right(stream.toLowerCase)
      case _ => Left(RunlogError.InvalidStream(stream))
    }

  def runsDir(cwd: Path): Path = cwd.resolve(".agent").resolve("runs")

  def list(cwd: Path): IO[RunlogError, List[RunMetadata]] =
    ZIO.attemptBlocking {
      val dir = runsDir(cwd)
      if (!Files.isDirectory(dir)) List.empty[RunMetadata]
      else {
        Files.list(dir).iterator().asScala
          .filter(p => p.toString.endsWith(".json"))
          .flatMap { p =>
            val content = Files.readString(p)
            content.fromJson[RunMetadata].toOption
          }
          .toList
          .sortBy(_.startedAt)(Ordering[java.time.Instant].reverse)
      }
    }.mapError(e => RunlogError.IoError(e.getMessage))

  def showMetadata(cwd: Path, runId: String): IO[RunlogError, RunMetadata] =
    for {
      _    <- ZIO.fromEither(validateRunId(runId))
      path  = runsDir(cwd).resolve(s"$runId.json")
      meta <- ZIO.attemptBlocking(Files.readString(path))
        .mapError(_ => RunlogError.NotFound(s"Run $runId not found"))
        .flatMap(content =>
          ZIO.fromEither(content.fromJson[RunMetadata])
            .mapError(e => RunlogError.IoError(s"Corrupt metadata: $e"))
        )
    } yield meta

  def head(cwd: Path, runId: String, stream: String, lines: Int): IO[RunlogError, String] =
    readStream(cwd, runId, stream).map { content =>
      content.linesIterator.take(lines).mkString("\n")
    }

  def tail(cwd: Path, runId: String, stream: String, lines: Int): IO[RunlogError, String] =
    readStream(cwd, runId, stream).map { content =>
      val allLines = content.linesIterator.toVector
      allLines.takeRight(lines).mkString("\n")
    }

  def grep(cwd: Path, runId: String, stream: String, pattern: String): IO[RunlogError, List[String]] =
    readStream(cwd, runId, stream).map { content =>
      content.linesIterator
        .filter(_.contains(pattern))
        .toList
    }

  def range(cwd: Path, runId: String, stream: String, startLine: Int, endLine: Int): IO[RunlogError, String] =
    readStream(cwd, runId, stream).map { content =>
      content.linesIterator
        .zipWithIndex
        .filter { case (_, idx) => idx >= (startLine - 1) && idx < endLine }
        .map(_._1)
        .mkString("\n")
    }

  private def readStream(cwd: Path, runId: String, stream: String): IO[RunlogError, String] =
    for {
      _ <- ZIO.fromEither(validateRunId(runId))
      _ <- ZIO.fromEither(validateStream(stream))
      path = runsDir(cwd).resolve(s"$runId.$stream")
      content <- ZIO.attemptBlocking {
        if (Files.exists(path)) Files.readString(path)
        else ""
      }.mapError(e => RunlogError.IoError(e.getMessage))
    } yield content
}
