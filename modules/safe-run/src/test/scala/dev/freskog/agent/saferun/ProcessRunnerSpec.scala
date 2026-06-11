package dev.freskog.agent.saferun

import dev.freskog.agent.common._

import zio._
import zio.json._
import zio.test._

import java.nio.file.{Files, Path}

object ProcessRunnerSpec extends ZIOSpecDefault {

  private def withTempDir(f: Path => ZIO[Any, Any, TestResult]): ZIO[Any, Any, TestResult] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("safe-run-test-"))
    )(dir => ZIO.attemptBlocking {
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
    }.ignore)(f)

  private val hasSetsid: Boolean = {
    try {
      val pb = new ProcessBuilder("which", "setsid")
      pb.redirectErrorStream(true)
      pb.start().waitFor() == 0
    } catch { case _: Exception => false }
  }

  private val whenSetsid = if (hasSetsid) TestAspect.identity else TestAspect.ignore

  def spec = suite("ProcessRunnerSpec")(
    test("injects extra environment variables into the child process") {
      withTempDir { dir =>
        val config = RunConfig("echo channel=$MYCROFT_CHANNEL", Shell.Bash, dir, 10, env = Map("MYCROFT_CHANNEL" -> "fred"))
        for {
          meta <- ProcessRunner.run(config)
        } yield assertTrue(meta.exitCode.contains(0), meta.stdoutHead.contains("channel=fred"))
      }
    } @@ whenSetsid,
    test("small stdout") {
      withTempDir { dir =>
        val config = RunConfig("echo hello", Shell.Bash, dir, 10)
        for {
          meta <- ProcessRunner.run(config)
        } yield assertTrue(
          meta.exitCode.contains(0),
          !meta.timedOut,
          meta.stdoutHead.trim == "hello",
          meta.stderrBytes == 0L
        )
      }
    },
    test("large stdout") {
      withTempDir { dir =>
        val config = RunConfig("seq 1 10000", Shell.Bash, dir, 10)
        for {
          meta <- ProcessRunner.run(config)
        } yield assertTrue(
          meta.exitCode.contains(0),
          meta.stdoutBytes > 0L,
          meta.stdoutHead.contains("1"),
          meta.stdoutTail.contains("10000")
        )
      }
    },
    test("stderr output") {
      withTempDir { dir =>
        val config = RunConfig("echo error >&2", Shell.Bash, dir, 10)
        for {
          meta <- ProcessRunner.run(config)
        } yield assertTrue(
          meta.exitCode.contains(0),
          meta.stderrHead.trim == "error",
          meta.stderrBytes > 0L
        )
      }
    },
    test("non-zero exit code still produces valid JSON") {
      withTempDir { dir =>
        val config = RunConfig("exit 42", Shell.Bash, dir, 10)
        for {
          meta <- ProcessRunner.run(config)
        } yield assertTrue(
          meta.exitCode.contains(42),
          !meta.timedOut
        )
      }
    },
    test("timeout kills process") {
      withTempDir { dir =>
        val config = RunConfig("sleep 60", Shell.Bash, dir, 2)
        for {
          meta <- ProcessRunner.run(config)
        } yield assertTrue(
          meta.timedOut,
          meta.exitCode.isEmpty,
          meta.termination.contains("timeout")
        )
      }
    } @@ TestAspect.timeout(30.seconds),
    test("invalid cwd fails") {
      val config = RunConfig("echo hi", Shell.Bash, Path.of("/nonexistent/dir"), 10)
      for {
        result <- ProcessRunner.run(config).either
      } yield assertTrue(
        result == Left(SafeRunError.InvalidCwd("/nonexistent/dir"))
      )
    } @@ TestAspect.withLiveClock,
    test("command with quotes and spaces") {
      withTempDir { dir =>
        val config = RunConfig("""echo "hello world" 'foo bar'""", Shell.Bash, dir, 10)
        for {
          meta <- ProcessRunner.run(config)
        } yield assertTrue(
          meta.exitCode.contains(0),
          meta.stdoutHead.trim == "hello world foo bar"
        )
      }
    },
    test("metadata JSON file is written") {
      withTempDir { dir =>
        import JsonCodecs._
        val config = RunConfig("echo test", Shell.Bash, dir, 10)
        for {
          meta <- ProcessRunner.run(config)
          jsonContent <- ZIO.attemptBlocking(Files.readString(Path.of(meta.metadataLog)))
          parsed = jsonContent.fromJson[RunMetadata]
        } yield assertTrue(
          parsed.isRight,
          parsed.toOption.get.runId == meta.runId
        )
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ whenSetsid
}
