package dev.freskog.agent.runlog

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.json._
import zio.test._

import java.nio.file.{Files, Path}
import java.time.Instant

object LogReaderSpec extends ZIOSpecDefault {

  private val testRunId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  private val now = Instant.parse("2026-05-25T12:00:00Z")

  private val testMeta = RunMetadata(
    runId = testRunId,
    command = "echo hello",
    shell = "bash",
    cwd = "/tmp",
    startedAt = now,
    finishedAt = now.plusMillis(100),
    durationMs = 100,
    exitCode = Some(0),
    timedOut = false,
    termination = None,
    stdoutBytes = 6,
    stderrBytes = 0,
    stdoutHead = "hello",
    stdoutTail = "hello",
    stderrHead = "",
    stderrTail = "",
    stdoutLog = "",
    stderrLog = "",
    metadataLog = ""
  )

  private def withFixture(f: Path => ZIO[Any, Any, TestResult]): ZIO[Any, Any, TestResult] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking {
        val tmpDir = Files.createTempDirectory("runlog-test-")
        val runsDir = tmpDir.resolve(".agent").resolve("runs")
        Files.createDirectories(runsDir)

        Files.writeString(runsDir.resolve(s"$testRunId.json"), testMeta.toJson)
        Files.writeString(runsDir.resolve(s"$testRunId.stdout"), (1 to 100).map(i => s"line $i").mkString("\n"))
        Files.writeString(runsDir.resolve(s"$testRunId.stderr"), "warning: something\nerror: bad thing\n")

        tmpDir
      }
    )(dir => ZIO.attemptBlocking {
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    }.ignore)(f)

  def spec = suite("LogReaderSpec")(
    test("list runs returns metadata") {
      withFixture { cwd =>
        for {
          runs <- LogReader.list(cwd)
        } yield assertTrue(
          runs.length == 1,
          runs.headOption.map(_.runId).contains(testRunId)
        )
      }
    },
    test("head returns first N lines of stdout") {
      withFixture { cwd =>
        for {
          content <- LogReader.head(cwd, testRunId, "stdout", 3)
        } yield assertTrue(content == "line 1\nline 2\nline 3")
      }
    },
    test("tail returns last N lines of stdout") {
      withFixture { cwd =>
        for {
          content <- LogReader.tail(cwd, testRunId, "stdout", 3)
        } yield assertTrue(content == "line 98\nline 99\nline 100")
      }
    },
    test("grep finds matching lines") {
      withFixture { cwd =>
        for {
          matches <- LogReader.grep(cwd, testRunId, "stderr", "error")
        } yield assertTrue(
          matches.length == 1,
          matches.headOption.contains("error: bad thing")
        )
      }
    },
    test("range returns lines in range") {
      withFixture { cwd =>
        for {
          content <- LogReader.range(cwd, testRunId, "stdout", 5, 7)
        } yield assertTrue(content == "line 5\nline 6\nline 7")
      }
    },
    test("invalid run_id is rejected") {
      withFixture { cwd =>
        for {
          result <- LogReader.head(cwd, "not-a-uuid", "stdout", 10).either
        } yield assertTrue(result == Left(RunlogError.InvalidRunId("not-a-uuid")))
      }
    },
    test("path traversal in run_id is rejected") {
      withFixture { cwd =>
        for {
          result <- LogReader.head(cwd, "../../../etc/passwd", "stdout", 10).either
        } yield assertTrue(result.isLeft)
      }
    },
    test("invalid stream is rejected") {
      withFixture { cwd =>
        for {
          result <- LogReader.head(cwd, testRunId, "stdin", 10).either
        } yield assertTrue(result == Left(RunlogError.InvalidStream("stdin")))
      }
    },
    test("missing log returns empty content") {
      withFixture { cwd =>
        val missingId = "11111111-2222-3333-4444-555555555555"
        for {
          content <- LogReader.head(cwd, missingId, "stdout", 10)
        } yield assertTrue(content == "")
      }
    },
    test("list on empty dir returns empty list") {
      ZIO.acquireReleaseWith(
        ZIO.attemptBlocking(Files.createTempDirectory("runlog-empty-"))
      )(dir => ZIO.attemptBlocking(Files.deleteIfExists(dir)).ignore) { cwd =>
        for {
          runs <- LogReader.list(cwd)
        } yield assertTrue(runs.isEmpty)
      }
    }
  )
}
