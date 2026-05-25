package dev.freskog.agent.common

import zio._
import zio.json._

object JsonOutput {
  import JsonCodecs._

  def ok[A: JsonEncoder](data: A): UIO[Unit] =
    Console.printLine(data.toJson).orDie

  def error(message: String, detail: Option[String] = None): UIO[Unit] =
    Console.printLine(ErrorResponse(message, detail).toJson).orDie

  def stderr(message: String): UIO[Unit] =
    Console.printLineError(message).orDie
}
