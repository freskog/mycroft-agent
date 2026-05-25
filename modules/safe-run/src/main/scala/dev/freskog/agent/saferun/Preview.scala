package dev.freskog.agent.saferun

import zio._

import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path}

object Preview {

  val DefaultMaxLines = 80
  val DefaultMaxBytes = 16 * 1024

  def extractHead(path: Path, maxLines: Int = DefaultMaxLines, maxBytes: Int = DefaultMaxBytes): Task[String] =
    ZIO.attemptBlocking {
      val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

      val bytes = Files.readAllBytes(path)
      val content = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString

      val lines = content.linesIterator.take(maxLines).toVector
      val joined = lines.mkString("\n")
      if (joined.length > maxBytes) joined.take(maxBytes) else joined
    }.catchAll(_ => ZIO.succeed(""))

  def extractTail(path: Path, maxLines: Int = DefaultMaxLines, maxBytes: Int = DefaultMaxBytes): Task[String] =
    ZIO.attemptBlocking {
      val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

      val bytes = Files.readAllBytes(path)
      val content = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString

      val allLines = content.linesIterator.toVector
      val lines = allLines.takeRight(maxLines)
      val joined = lines.mkString("\n")
      if (joined.length > maxBytes) joined.takeRight(maxBytes) else joined
    }.catchAll(_ => ZIO.succeed(""))
}
