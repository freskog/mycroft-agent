package dev.freskog.agent.person.cli

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.cli._
import zio.json._

object Main extends ZIOCliDefault {

  sealed trait Cmd extends Product with Serializable
  object Cmd {
    case object Health extends Cmd

    final case class CommitmentPropose(
      owner: String, scope: String, text: String,
      source: String, evidence: String, due: Option[String]
    ) extends Cmd
    final case class CommitmentList(
      owner: Option[String], scope: Option[String], status: Option[String]
    ) extends Cmd

    final case class MemoryPropose(
      person: String, scope: String, kind: String, text: String, source: String
    ) extends Cmd

    final case class ApprovalRequest(
      actionType: String, scope: String, payloadJson: String
    ) extends Cmd

    final case class GoalPropose(
      owner: String, scope: String, title: String, outcome: String,
      evidenceRule: String, constraintsJson: Option[String], source: Option[String]
    ) extends Cmd
    final case class GoalList(
      owner: Option[String], scope: Option[String], status: Option[String]
    ) extends Cmd
    final case class GoalShow(id: String)                                              extends Cmd
    final case class GoalStatusUpdate(id: String, to: String, reason: Option[String])  extends Cmd
    final case class GoalEvidenceAppend(id: String, kind: String, ref: String, note: Option[String]) extends Cmd

    final case class MemoryAccept(id: String)                                       extends Cmd
    final case class MemoryReject(id: String, reason: Option[String])               extends Cmd
    final case class MemoryArchive(id: String)                                      extends Cmd
    final case class MemorySupersede(newId: String, oldId: String)                  extends Cmd
    final case class MemorySearch(query: String, scope: Option[String], person: Option[String], kind: Option[String], asOf: Option[String], limit: Option[Int]) extends Cmd
    final case class MemoryContext(scope: Option[String], person: Option[String], factLimit: Option[Int], eventLimit: Option[Int]) extends Cmd
    final case class MemoryConflicts(scope: Option[String], person: Option[String], kind: String, text: String) extends Cmd
    final case class MemoryConsolidate(scope: String, since: Option[String])        extends Cmd

    final case class EventRecord(actor: Option[String], action: String, category: String, scope: Option[String], targetType: Option[String], targetId: Option[String], text: Option[String], payloadJson: Option[String]) extends Cmd
    final case class EventLog(scope: Option[String], category: Option[String], since: Option[String], until: Option[String], limit: Option[Int]) extends Cmd
    final case class EventSearch(query: String, scope: Option[String], category: Option[String], since: Option[String], limit: Option[Int]) extends Cmd
  }

  // --- commitment ---
  private val commitmentPropose = Command(
    "propose",
    Options.text("owner") ++ Options.text("scope") ++ Options.text("text") ++
      Options.text("source") ++ Options.text("evidence") ++ Options.text("due").optional
  ).map { case (owner, scope, text, source, evidence, due) =>
    Cmd.CommitmentPropose(owner, scope, text, source, evidence, due)
  }

  private val commitmentList = Command(
    "list",
    Options.text("owner").optional ++ Options.text("scope").optional ++ Options.text("status").optional
  ).map { case (owner, scope, status) => Cmd.CommitmentList(owner, scope, status) }

  private val commitment = Command("commitment").subcommands(commitmentPropose, commitmentList)

  // --- memory ---
  private val memoryPropose = Command(
    "propose",
    Options.text("person") ++ Options.text("scope") ++ Options.text("kind") ++
      Options.text("text") ++ Options.text("source")
  ).map { case (person, scope, kind, text, source) =>
    Cmd.MemoryPropose(person, scope, kind, text, source)
  }

  // --- approval ---
  private val approvalRequest = Command(
    "request",
    Options.text("action-type") ++ Options.text("scope") ++ Options.text("payload-json")
  ).map { case (actionType, scope, payloadJson) =>
    Cmd.ApprovalRequest(actionType, scope, payloadJson)
  }
  private val approval = Command("approval").subcommands(approvalRequest)

  // --- goal ---
  private val goalPropose = Command(
    "propose",
    Options.text("owner") ++ Options.text("scope") ++ Options.text("title") ++
      Options.text("outcome") ++ Options.text("evidence-rule") ++
      Options.text("constraints-json").optional ++ Options.text("source").optional
  ).map { case (owner, scope, title, outcome, evidenceRule, constraints, source) =>
    Cmd.GoalPropose(owner, scope, title, outcome, evidenceRule, constraints, source)
  }

  private val goalList = Command(
    "list",
    Options.text("owner").optional ++ Options.text("scope").optional ++ Options.text("status").optional
  ).map { case (owner, scope, status) => Cmd.GoalList(owner, scope, status) }

  private val goalShow = Command("show", Args.text("id")).map(id => Cmd.GoalShow(id))

  private val goalStatus = Command(
    "status",
    Options.text("to") ++ Options.text("reason").optional,
    Args.text("id")
  ).map { case ((to, reason), id) => Cmd.GoalStatusUpdate(id, to, reason) }

  private val goalEvidence = Command(
    "evidence",
    Options.text("kind") ++ Options.text("ref") ++ Options.text("note").optional,
    Args.text("id")
  ).map { case ((kind, ref, note), id) => Cmd.GoalEvidenceAppend(id, kind, ref, note) }

  // Discoverable alias for `status --to cancelled`: a soft-remove that keeps the
  // audit trail. Reuses the GoalStatusUpdate handler / endpoint.
  private val goalCancel = Command(
    "cancel",
    Options.text("reason").optional,
    Args.text("id")
  ).map { case (reason, id) => Cmd.GoalStatusUpdate(id, "cancelled", reason) }

  private val goal = Command("goal").subcommands(goalPropose, goalList, goalShow, goalStatus, goalCancel, goalEvidence)

  // --- memory (extended) ---
  private val memoryAccept    = Command("accept",  Args.text("id")).map(id => Cmd.MemoryAccept(id))
  private val memoryReject    = Command("reject",  Options.text("reason").optional, Args.text("id")).map { case (reason, id) => Cmd.MemoryReject(id, reason) }
  private val memoryArchive   = Command("archive", Args.text("id")).map(id => Cmd.MemoryArchive(id))
  private val memorySupersede = Command(
    "supersede",
    Options.text("new") ++ Options.text("old")
  ).map { case (nu, ol) => Cmd.MemorySupersede(nu, ol) }
  private val memorySearch = Command(
    "search",
    Options.text("scope").optional ++ Options.text("person").optional ++
      Options.text("kind").optional ++ Options.text("as-of").optional ++ Options.integer("limit").optional,
    Args.text("query")
  ).map { case ((scope, person, kind, asOf, limit), q) =>
    Cmd.MemorySearch(q, scope, person, kind, asOf, limit.map(_.toInt))
  }
  private val memoryContext = Command(
    "context",
    Options.text("scope").optional ++ Options.text("person").optional ++
      Options.integer("fact-limit").optional ++ Options.integer("event-limit").optional
  ).map { case (scope, person, factLim, eventLim) =>
    Cmd.MemoryContext(scope, person, factLim.map(_.toInt), eventLim.map(_.toInt))
  }
  private val memoryConflicts = Command(
    "conflicts",
    Options.text("scope").optional ++ Options.text("person").optional ++
      Options.text("kind") ++ Options.text("text")
  ).map { case (scope, person, kind, text) => Cmd.MemoryConflicts(scope, person, kind, text) }
  private val memoryConsolidate = Command(
    "consolidate",
    Options.text("scope") ++ Options.text("since").optional
  ).map { case (scope, since) => Cmd.MemoryConsolidate(scope, since) }

  private val memoryExt = Command("memory").subcommands(
    memoryPropose, memoryAccept, memoryReject, memoryArchive,
    memorySupersede, memorySearch, memoryContext, memoryConflicts, memoryConsolidate
  )

  // --- event ---
  private val eventRecord = Command(
    "record",
    Options.text("actor").optional ++ Options.text("action") ++ Options.text("category") ++
      Options.text("scope").optional ++ Options.text("target-type").optional ++
      Options.text("target-id").optional ++ Options.text("text").optional ++
      Options.text("payload-json").optional
  ).map { case (actor, action, category, scope, ttype, tid, text, payload) =>
    Cmd.EventRecord(actor, action, category, scope, ttype, tid, text, payload)
  }
  private val eventLog = Command(
    "log",
    Options.text("scope").optional ++ Options.text("category").optional ++
      Options.text("since").optional ++ Options.text("until").optional ++
      Options.integer("limit").optional
  ).map { case (scope, category, since, until, limit) =>
    Cmd.EventLog(scope, category, since, until, limit.map(_.toInt))
  }
  private val eventSearch = Command(
    "search",
    Options.text("scope").optional ++ Options.text("category").optional ++
      Options.text("since").optional ++ Options.integer("limit").optional,
    Args.text("query")
  ).map { case ((scope, category, since, limit), q) =>
    Cmd.EventSearch(q, scope, category, since, limit.map(_.toInt))
  }
  private val event = Command("event").subcommands(eventRecord, eventLog, eventSearch)

  // --- top-level ---
  private val health = Command("health").map(_ => Cmd.Health)

  private val personCommand = Command("person").subcommands(health, commitment, memoryExt, approval, goal, event)

  val cliApp = CliApp.make(
    name = "person",
    version = "0.1.0",
    summary = HelpDoc.Span.text("Sandbox-safe CLI client for person-service"),
    command = personCommand
  ) { (cmd: Cmd) =>
    dispatch(cmd).catchAll(JsonOutput.agentError)
  }

  private def dispatch(cmd: Cmd): IO[AgentError, Unit] = cmd match {
    case Cmd.Health =>
      HttpClient.get("/health").flatMap(out => Console.printLine(out).orDie)

    case Cmd.CommitmentPropose(owner, scope, text, source, evidence, due) =>
      val body = jsonObj(
        "ownerPersonId" -> owner.toJson,
        "scopeId"       -> scope.toJson,
        "text"          -> text.toJson,
        "source"        -> source.toJson,
        "evidence"      -> evidence.toJson,
        "dueAt"         -> due.toJson
      )
      HttpClient.post("/commitments/propose", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.CommitmentList(owner, scope, status) =>
      HttpClient.get("/commitments", paramsMap("owner" -> owner, "scope" -> scope, "status" -> status))
        .flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryPropose(person, scope, kind, text, source) =>
      for {
        memKind <- parseMemoryKind(kind)
        body     = jsonObj(
                     "personId"   -> (Some(person): Option[String]).toJson,
                     "scopeId"    -> (Some(scope): Option[String]).toJson,
                     "kind"       -> memKind.toJson,
                     "text"       -> text.toJson,
                     "source"     -> source.toJson,
                     "confidence" -> (None: Option[Double]).toJson
                   )
        out     <- HttpClient.post("/memory/propose", body)
        _       <- Console.printLine(out).orDie
      } yield ()

    case Cmd.ApprovalRequest(actionType, scope, payloadJson) =>
      val body = jsonObj(
        "requestedBy"      -> "\"agent\"",
        "requiredPersonId" -> "null",
        "scopeId"          -> (Some(scope): Option[String]).toJson,
        "actionType"       -> actionType.toJson,
        "payloadJson"      -> payloadJson.toJson
      )
      HttpClient.post("/approvals/request", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.GoalPropose(owner, scope, title, outcome, evidenceRule, constraints, source) =>
      val body = jsonObj(
        "ownerPersonId"   -> owner.toJson,
        "scopeId"         -> scope.toJson,
        "title"           -> title.toJson,
        "outcome"         -> outcome.toJson,
        "evidenceRule"    -> evidenceRule.toJson,
        "constraintsJson" -> constraints.toJson,
        "source"          -> source.toJson
      )
      HttpClient.post("/goals", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.GoalList(owner, scope, status) =>
      HttpClient.get("/goals", paramsMap("owner" -> owner, "scope" -> scope, "status" -> status))
        .flatMap(out => Console.printLine(out).orDie)

    case Cmd.GoalShow(id) =>
      HttpClient.get(s"/goals/$id").flatMap(out => Console.printLine(out).orDie)

    case Cmd.GoalStatusUpdate(id, to, reason) =>
      for {
        status <- parseGoalStatus(to)
        body    = jsonObj(
                    "status"        -> status.toJson,
                    "blockedReason" -> reason.toJson
                  )
        out    <- HttpClient.post(s"/goals/$id/status", body)
        _      <- Console.printLine(out).orDie
      } yield ()

    case Cmd.GoalEvidenceAppend(id, kind, ref, note) =>
      val body = jsonObj(
        "kind" -> kind.toJson,
        "ref"  -> ref.toJson,
        "note" -> note.toJson
      )
      HttpClient.post(s"/goals/$id/evidence", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryAccept(id) =>
      HttpClient.post(s"/memory/$id/accept", "{}").flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryReject(id, reason) =>
      val body = jsonObj("reason" -> reason.toJson)
      HttpClient.post(s"/memory/$id/reject", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryArchive(id) =>
      HttpClient.post(s"/memory/$id/archive", "{}").flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemorySupersede(newId, oldId) =>
      val body = jsonObj("newId" -> newId.toJson, "oldId" -> oldId.toJson)
      HttpClient.post("/memory/supersede", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemorySearch(query, scope, person, kind, asOf, limit) =>
      val params = paramsMap(
        "q"      -> Some(query),
        "scope"  -> scope,
        "person" -> person,
        "kind"   -> kind,
        "as_of"  -> asOf,
        "limit"  -> limit.map(_.toString)
      )
      HttpClient.get("/memory/search", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryContext(scope, person, factLim, eventLim) =>
      val params = paramsMap(
        "scope"       -> scope,
        "person"      -> person,
        "fact_limit"  -> factLim.map(_.toString),
        "event_limit" -> eventLim.map(_.toString)
      )
      HttpClient.get("/memory/context", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryConflicts(scope, person, kind, text) =>
      val params = paramsMap(
        "scope"  -> scope,
        "person" -> person,
        "kind"   -> Some(kind),
        "text"   -> Some(text)
      )
      HttpClient.get("/memory/conflicts", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryConsolidate(scope, since) =>
      val body = jsonObj("scopeId" -> scope.toJson, "since" -> since.toJson)
      HttpClient.post("/memory/consolidate", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.EventRecord(actor, action, category, scope, ttype, tid, text, payload) =>
      val body = jsonObj(
        "actor"       -> actor.getOrElse("agent").toJson,
        "action"      -> action.toJson,
        "category"    -> category.toJson,
        "scopeId"     -> scope.toJson,
        "targetType"  -> ttype.toJson,
        "targetId"    -> tid.toJson,
        "text"        -> text.toJson,
        "payloadJson" -> payload.toJson
      )
      HttpClient.post("/events", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.EventLog(scope, category, since, until, limit) =>
      val params = paramsMap(
        "scope"    -> scope,
        "category" -> category,
        "since"    -> since,
        "until"    -> until,
        "limit"    -> limit.map(_.toString)
      )
      HttpClient.get("/events", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.EventSearch(query, scope, category, since, limit) =>
      val params = paramsMap(
        "q"        -> Some(query),
        "scope"    -> scope,
        "category" -> category,
        "since"    -> since,
        "limit"    -> limit.map(_.toString)
      )
      HttpClient.get("/events/search", params).flatMap(out => Console.printLine(out).orDie)
  }

  private def jsonObj(fields: (String, String)*): String =
    fields.map { case (k, v) => s""""$k":$v""" }.mkString("{", ",", "}")

  private def paramsMap(pairs: (String, Option[String])*): Map[String, String] =
    pairs.collect { case (k, Some(v)) => k -> v }.toMap

  private def parseMemoryKind(s: String): IO[AgentError, MemoryKind] =
    ZIO.fromEither(MemoryKind.fromString(s)).mapError(AgentError.Validation)

  private def parseGoalStatus(s: String): IO[AgentError, GoalStatus] =
    ZIO.fromEither(GoalStatus.fromString(s)).mapError(AgentError.Validation)

  /** Pipe a CLI request through HTTP and print the response, wrapping
   *  IO errors (e.g. `Console.printLine`) into `AgentError`. */
  private def httpEcho(io: IO[AgentError, String]): IO[AgentError, Unit] =
    io.flatMap(out => Console.printLine(out).orDie)
}
