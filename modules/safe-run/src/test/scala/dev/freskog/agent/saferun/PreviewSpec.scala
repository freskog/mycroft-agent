package dev.freskog.agent.saferun

import zio._
import zio.test._

import java.nio.file.{Files, Path}

object PreviewSpec extends ZIOSpecDefault {

  private def withTempFile(content: Array[Byte])(f: Path => ZIO[Any, Any, TestResult]): ZIO[Any, Any, TestResult] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking {
        val tmp = Files.createTempFile("preview-test-", ".txt")
        Files.write(tmp, content)
        tmp
      }
    )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).ignore)(f)

  def spec = suite("PreviewSpec")(
    test("extractHead returns first N lines") {
      val content = (1 to 200).map(i => s"line $i").mkString("\n")
      withTempFile(content.getBytes("UTF-8")) { path =>
        for {
          head <- Preview.extractHead(path, maxLines = 5)
        } yield assertTrue(
          head == "line 1\nline 2\nline 3\nline 4\nline 5"
        )
      }
    },
    test("extractTail returns last N lines") {
      val content = (1 to 200).map(i => s"line $i").mkString("\n")
      withTempFile(content.getBytes("UTF-8")) { path =>
        for {
          tail <- Preview.extractTail(path, maxLines = 3)
        } yield assertTrue(
          tail == "line 198\nline 199\nline 200"
        )
      }
    },
    test("extractHead respects maxBytes") {
      val content = (1 to 1000).map(i => s"line $i with some padding text").mkString("\n")
      withTempFile(content.getBytes("UTF-8")) { path =>
        for {
          head <- Preview.extractHead(path, maxLines = 1000, maxBytes = 100)
        } yield assertTrue(head.length <= 100)
      }
    },
    test("extractTail respects maxBytes") {
      val content = (1 to 1000).map(i => s"line $i with some padding text").mkString("\n")
      withTempFile(content.getBytes("UTF-8")) { path =>
        for {
          tail <- Preview.extractTail(path, maxLines = 1000, maxBytes = 100)
        } yield assertTrue(tail.length <= 100)
      }
    },
    test("handles binary-ish content without crashing") {
      val binary = Array.tabulate[Byte](256)(i => i.toByte)
      withTempFile(binary) { path =>
        for {
          head <- Preview.extractHead(path)
        } yield assertTrue(head.nonEmpty)
      }
    },
    test("returns empty string for missing file") {
      for {
        head <- Preview.extractHead(Path.of("/nonexistent/path/file.txt"))
      } yield assertTrue(head == "")
    },
    test("very long single line is bounded") {
      val longLine = "x" * 100000
      withTempFile(longLine.getBytes("UTF-8")) { path =>
        for {
          head <- Preview.extractHead(path, maxLines = 80, maxBytes = 16384)
        } yield assertTrue(head.length <= 16384)
      }
    }
  )
}
