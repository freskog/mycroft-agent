package dev.freskog.agent.common

import zio._
import zio.json._

object JsonOutput {
  import JsonCodecs._

  def ok[A: JsonEncoder](data: A): UIO[Unit] =
    Console.printLine(data.toJson).orDie

  /** Emit a free-form error envelope. Kept for callers that don't have an
   *  AgentError on hand; prefer `agentError` when the structured ADT is
   *  available so downstream consumers can discriminate on `type`. */
  def error(message: String, detail: Option[String] = None): UIO[Unit] =
    Console.printLine(ErrorResponse(message, detail).toJson).orDie

  def agentError(e: AgentError): UIO[Unit] =
    Console.printLine(e.toJson).orDie

  def stderr(message: String): UIO[Unit] =
    Console.printLineError(message).orDie
}
