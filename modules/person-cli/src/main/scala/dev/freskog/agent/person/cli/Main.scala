package dev.freskog.agent.person.cli

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.cli._
import zio.json._

object Main extends ZIOCliDefault {

  // --- Command definitions ---

  val healthCommand = Command("health").map(_ => ())

  // commitment propose
  val commitmentProposeCommand = Command(
    "propose",
    Options.text("owner") ++ Options.text("scope") ++ Options.text("text") ++
      Options.text("source") ++ Options.text("evidence") ++ Options.text("due").optional
  )

  // commitment list
  val commitmentListCommand = Command(
    "list",
    Options.text("owner").optional ++ Options.text("scope").optional ++ Options.text("status").optional
  )

  val commitmentCommand = Command("commitment").subcommands(commitmentProposeCommand, commitmentListCommand)

  // memory propose
  val memoryProposeCommand = Command(
    "propose",
    Options.text("person") ++ Options.text("scope") ++ Options.text("kind") ++
      Options.text("text") ++ Options.text("source")
  )
  val memoryCommand = Command("memory").subcommands(memoryProposeCommand)

  // approval request
  val approvalRequestCommand = Command(
    "request",
    Options.text("action-type") ++ Options.text("scope") ++ Options.text("payload-json")
  )
  val approvalCommand = Command("approval").subcommands(approvalRequestCommand)

  // top-level: person <subcommand>
  val personCommand = Command("person").subcommands(healthCommand, commitmentCommand, memoryCommand, approvalCommand)

  val cliApp = CliApp.make(
    name = "person",
    version = "0.1.0",
    summary = HelpDoc.Span.text("Sandbox-safe CLI client for person-service"),
    command = personCommand
  ) { input =>
    dispatch(input).catchAll { e =>
      JsonOutput.error(e.getMessage, None)
    }
  }

  private def dispatch(input: Any): Task[Unit] = input match {
    // health is Unit
    case () => HttpClient.get("/health").flatMap(Console.printLine(_)).unit

    // commitment subcommands return Either
    case Left(proposeArgs) =>
      val (owner, scope, text, source, evidence, due) = proposeArgs.asInstanceOf[(String, String, String, String, String, Option[String])]
      val payload = Map(
        "ownerPersonId" -> owner.toJson,
        "scopeId" -> scope.toJson,
        "text" -> text.toJson,
        "source" -> source.toJson,
        "evidence" -> evidence.toJson,
        "dueAt" -> due.toJson
      )
      val json = s"{${payload.map { case (k, v) => s""""$k":$v""" }.mkString(",")}}"
      HttpClient.post("/commitments/propose", json).flatMap(Console.printLine(_)).unit

    case Right(listOrMemoryOrApproval) =>
      dispatchRight(listOrMemoryOrApproval)

    case other =>
      ZIO.fail(new RuntimeException(s"Unknown command shape: ${other.getClass}"))
  }

  private def dispatchRight(input: Any): Task[Unit] = input match {
    case Left(listArgs) =>
      val (owner, scope, status) = listArgs.asInstanceOf[(Option[String], Option[String], Option[String])]
      val params = Map("owner" -> owner, "scope" -> scope, "status" -> status).collect { case (k, Some(v)) => k -> v }
      HttpClient.get("/commitments", params).flatMap(Console.printLine(_)).unit

    case Right(memoryOrApproval) =>
      dispatchMemoryOrApproval(memoryOrApproval)

    case other =>
      ZIO.fail(new RuntimeException(s"Unknown sub-command shape: ${other.getClass}"))
  }

  private def dispatchMemoryOrApproval(input: Any): Task[Unit] = input match {
    case Left(memArgs) =>
      val (person, scope, kind, text, source) = memArgs.asInstanceOf[(String, String, String, String, String)]
      val memKind = MemoryKind.fromString(kind).getOrElse(throw new RuntimeException(s"Invalid kind: $kind"))
      val payload = Map(
        "personId" -> (Some(person): Option[String]).toJson,
        "scopeId" -> (Some(scope): Option[String]).toJson,
        "kind" -> memKind.toJson,
        "text" -> text.toJson,
        "source" -> source.toJson,
        "confidence" -> (None: Option[Double]).toJson
      )
      val json = s"{${payload.map { case (k, v) => s""""$k":$v""" }.mkString(",")}}"
      HttpClient.post("/memory/propose", json).flatMap(Console.printLine(_)).unit

    case Right(approvalArgs) =>
      val (actionType, scope, payloadJson) = approvalArgs.asInstanceOf[(String, String, String)]
      val reqBody = Map(
        "requestedBy" -> "\"agent\"",
        "requiredPersonId" -> "null",
        "scopeId" -> (Some(scope): Option[String]).toJson,
        "actionType" -> actionType.toJson,
        "payloadJson" -> payloadJson.toJson
      )
      val json = s"{${reqBody.map { case (k, v) => s""""$k":$v""" }.mkString(",")}}"
      HttpClient.post("/approvals/request", json).flatMap(Console.printLine(_)).unit

    case other =>
      ZIO.fail(new RuntimeException(s"Unknown memory/approval shape: ${other.getClass}"))
  }
}
