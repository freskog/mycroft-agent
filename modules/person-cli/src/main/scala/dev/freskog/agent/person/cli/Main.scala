package dev.freskog.agent.person.cli

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.cli._
import zio.json._
import zio.json.ast.Json

import java.net.URI

object Main extends ZIOCliDefault {

  sealed trait Cmd extends Product with Serializable
  object Cmd {
    case object Health extends Cmd

    final case class PersonCreate(
      id: String, displayName: String, timezone: String, locale: Option[String]
    ) extends Cmd
    case object PersonList extends Cmd

    final case class CommitmentPropose(
      owner: String, text: String,
      source: String, evidence: String, due: Option[String]
    ) extends Cmd
    final case class CommitmentList(
      owner: Option[String], status: Option[String]
    ) extends Cmd

    final case class MemoryPropose(
      person: String, kind: String, text: String, source: String,
      confidence: Option[BigDecimal], validFrom: Option[String], validUntil: Option[String]
    ) extends Cmd

    final case class ApprovalRequest(
      actionType: String, payloadJson: String
    ) extends Cmd

    final case class GoalPropose(
      owner: String, title: String, outcome: String,
      evidenceRule: String, constraintsJson: Option[String], source: Option[String]
    ) extends Cmd
    final case class GoalList(
      owner: Option[String], status: Option[String]
    ) extends Cmd
    final case class GoalShow(id: String)                                              extends Cmd
    final case class GoalStatusUpdate(id: String, to: String, reason: Option[String])  extends Cmd
    final case class GoalEvidenceAppend(id: String, kind: String, ref: String, note: Option[String]) extends Cmd

    final case class MemoryAccept(id: String)                                       extends Cmd
    final case class MemoryReject(id: String, reason: Option[String])               extends Cmd
    final case class MemoryArchive(id: String)                                      extends Cmd
    final case class MemorySupersede(newId: String, oldId: String)                  extends Cmd
    final case class MemorySearch(query: String, person: Option[String], kind: Option[String], asOf: Option[String], limit: Option[Int]) extends Cmd
    final case class MemoryContext(person: Option[String], factLimit: Option[Int], eventLimit: Option[Int]) extends Cmd
    final case class MemoryConflicts(person: Option[String], kind: String, text: String) extends Cmd
    final case class MemoryConsolidate(since: Option[String])                       extends Cmd
    final case class MemoryProfile(limit: Option[Int])                              extends Cmd
    final case class MemoryList(person: Option[String], kind: Option[String], status: Option[String]) extends Cmd

    final case class EntityPropose(kind: String, name: String, attributesJson: Option[String], source: String, confidence: Option[BigDecimal]) extends Cmd
    final case class EntityList(kind: Option[String], status: Option[String])       extends Cmd
    final case class EntityResolve(name: String)                                    extends Cmd
    final case class EntityAccept(id: String)                                       extends Cmd
    final case class EntityReject(id: String, reason: Option[String])               extends Cmd
    final case class EntitySupersede(newId: String, oldId: String)                  extends Cmd

    final case class RelationshipPropose(
      from: String, fromKind: String, relType: String, to: String, toKind: String,
      source: String, confidence: Option[BigDecimal], note: Option[String],
      validFrom: Option[String], validUntil: Option[String]
    ) extends Cmd
    final case class RelationshipList(from: Option[String], to: Option[String], relType: Option[String], status: Option[String], asOf: Option[String]) extends Cmd
    final case class RelationshipAccept(id: String)                                 extends Cmd
    final case class RelationshipReject(id: String, reason: Option[String])         extends Cmd
    final case class RelationshipSupersede(newId: String, oldId: String)            extends Cmd

    case object Household extends Cmd

    final case class Pending(source: Option[String], nodeType: Option[String], kind: Option[String]) extends Cmd
    final case class AcceptAll(source: Option[String], nodeType: Option[String], kind: Option[String], ids: Option[String]) extends Cmd

    final case class EventRecord(actor: Option[String], action: String, category: String, targetType: Option[String], targetId: Option[String], text: Option[String], payloadJson: Option[String]) extends Cmd
    final case class EventLog(category: Option[String], since: Option[String], until: Option[String], limit: Option[Int]) extends Cmd
    final case class EventSearch(query: String, category: Option[String], since: Option[String], limit: Option[Int]) extends Cmd

    final case class GmailAuth(owner: String) extends Cmd
    final case class GmailSync(owner: String, since: Option[String]) extends Cmd
    final case class InboxList(owner: String, status: Option[String], limit: Option[Int], order: Option[String]) extends Cmd
    final case class InboxShow(id: String) extends Cmd
    final case class InboxDownload(id: String, out: String, attachment: Option[String]) extends Cmd
    final case class InboxSkip(id: String) extends Cmd
    final case class InboxMarkTriaged(id: String, sourceEventId: Option[String]) extends Cmd
    final case class CalendarAgenda(owner: String, days: Option[Int], from: Option[String], to: Option[String]) extends Cmd
  }

  // --- commitment ---
  private val commitmentPropose = Command(
    "propose",
    Options.text("owner") ++ Options.text("text") ++
      Options.text("source") ++ Options.text("evidence") ++ Options.text("due").optional
  ).map { case (owner, text, source, evidence, due) =>
    Cmd.CommitmentPropose(owner, text, source, evidence, due)
  }

  private val commitmentList = Command(
    "list",
    Options.text("owner").optional ++ Options.text("status").optional
  ).map { case (owner, status) => Cmd.CommitmentList(owner, status) }

  private val commitment = Command("commitment").subcommands(commitmentPropose, commitmentList)

  // --- memory ---
  private val memoryPropose = Command(
    "propose",
    Options.text("person") ++ Options.text("kind") ++
      Options.text("text") ++ Options.text("source") ++
      Options.decimal("confidence").optional ++
      Options.text("valid-from").optional ++ Options.text("valid-until").optional
  ).map { case (person, kind, text, source, confidence, validFrom, validUntil) =>
    Cmd.MemoryPropose(person, kind, text, source, confidence, validFrom, validUntil)
  }

  // --- approval ---
  private val approvalRequest = Command(
    "request",
    Options.text("action-type") ++ Options.text("payload-json")
  ).map { case (actionType, payloadJson) =>
    Cmd.ApprovalRequest(actionType, payloadJson)
  }
  private val approval = Command("approval").subcommands(approvalRequest)

  // --- goal ---
  private val goalPropose = Command(
    "propose",
    Options.text("owner") ++ Options.text("title") ++
      Options.text("outcome") ++ Options.text("evidence-rule") ++
      Options.text("constraints-json").optional ++ Options.text("source").optional
  ).map { case (owner, title, outcome, evidenceRule, constraints, source) =>
    Cmd.GoalPropose(owner, title, outcome, evidenceRule, constraints, source)
  }

  private val goalList = Command(
    "list",
    Options.text("owner").optional ++ Options.text("status").optional
  ).map { case (owner, status) => Cmd.GoalList(owner, status) }

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
    Options.text("person").optional ++
      Options.text("kind").optional ++ Options.text("as-of").optional ++ Options.integer("limit").optional,
    Args.text("query")
  ).map { case ((person, kind, asOf, limit), q) =>
    Cmd.MemorySearch(q, person, kind, asOf, limit.map(_.toInt))
  }
  private val memoryContext = Command(
    "context",
    Options.text("person").optional ++
      Options.integer("fact-limit").optional ++ Options.integer("event-limit").optional
  ).map { case (person, factLim, eventLim) =>
    Cmd.MemoryContext(person, factLim.map(_.toInt), eventLim.map(_.toInt))
  }
  private val memoryConflicts = Command(
    "conflicts",
    Options.text("person").optional ++
      Options.text("kind") ++ Options.text("text")
  ).map { case (person, kind, text) => Cmd.MemoryConflicts(person, kind, text) }
  private val memoryConsolidate = Command(
    "consolidate",
    Options.text("since").optional
  ).map(since => Cmd.MemoryConsolidate(since))
  private val memoryProfile = Command(
    "profile",
    Options.integer("limit").optional
  ).map(limit => Cmd.MemoryProfile(limit.map(_.toInt)))
  private val memoryList = Command(
    "list",
    Options.text("person").optional ++ Options.text("kind").optional ++ Options.text("status").optional
  ).map { case (person, kind, status) => Cmd.MemoryList(person, kind, status) }

  private val memoryExt = Command("memory").subcommands(
    memoryPropose, memoryAccept, memoryReject, memoryArchive,
    memorySupersede, memorySearch, memoryContext, memoryConflicts, memoryConsolidate, memoryProfile, memoryList
  )

  // --- entity (household graph nodes) ---
  private val entityPropose = Command(
    "propose",
    Options.text("kind") ++ Options.text("name") ++
      Options.text("attributes-json").optional ++ Options.text("source") ++
      Options.decimal("confidence").optional
  ).map { case (kind, name, attrs, source, confidence) =>
    Cmd.EntityPropose(kind, name, attrs, source, confidence)
  }
  private val entityList = Command(
    "list",
    Options.text("kind").optional ++ Options.text("status").optional
  ).map { case (kind, status) => Cmd.EntityList(kind, status) }
  private val entityResolve = Command("resolve", Args.text("name")).map(n => Cmd.EntityResolve(n))
  private val entityAccept  = Command("accept", Args.text("id")).map(id => Cmd.EntityAccept(id))
  private val entityReject  = Command("reject", Options.text("reason").optional, Args.text("id")).map { case (reason, id) => Cmd.EntityReject(id, reason) }
  private val entitySupersede = Command(
    "supersede",
    Options.text("new") ++ Options.text("old")
  ).map { case (nu, ol) => Cmd.EntitySupersede(nu, ol) }

  private val entity = Command("entity").subcommands(
    entityPropose, entityList, entityResolve, entityAccept, entityReject, entitySupersede
  )

  // --- relationship (household graph edges) ---
  private val relationshipPropose = Command(
    "propose",
    Options.text("from") ++ Options.text("from-kind") ++ Options.text("type") ++
      Options.text("to") ++ Options.text("to-kind") ++ Options.text("source") ++
      Options.decimal("confidence").optional ++ Options.text("note").optional ++
      Options.text("valid-from").optional ++ Options.text("valid-until").optional
  ).map { case (from, fromKind, relType, to, toKind, source, confidence, note, validFrom, validUntil) =>
    Cmd.RelationshipPropose(from, fromKind, relType, to, toKind, source, confidence, note, validFrom, validUntil)
  }
  private val relationshipList = Command(
    "list",
    Options.text("from").optional ++ Options.text("to").optional ++
      Options.text("type").optional ++ Options.text("status").optional ++ Options.text("as-of").optional
  ).map { case (from, to, relType, status, asOf) => Cmd.RelationshipList(from, to, relType, status, asOf) }
  private val relationshipAccept = Command("accept", Args.text("id")).map(id => Cmd.RelationshipAccept(id))
  private val relationshipReject = Command("reject", Options.text("reason").optional, Args.text("id")).map { case (reason, id) => Cmd.RelationshipReject(id, reason) }
  private val relationshipSupersede = Command(
    "supersede",
    Options.text("new") ++ Options.text("old")
  ).map { case (nu, ol) => Cmd.RelationshipSupersede(nu, ol) }

  private val relationship = Command("relationship").subcommands(
    relationshipPropose, relationshipList, relationshipAccept, relationshipReject, relationshipSupersede
  )

  // Combined household snapshot (accepted, currently-active graph).
  private val household = Command("household").map(_ => Cmd.Household)

  // Review queue: list everything proposed (by source/type/kind), and accept in
  // bulk — by the same filters, or an explicit comma-separated --ids subset.
  private val pending = Command(
    "pending",
    Options.text("source").optional ++ Options.text("type").optional ++ Options.text("kind").optional
  ).map { case (source, nodeType, kind) => Cmd.Pending(source, nodeType, kind) }
  private val acceptAll = Command(
    "accept-all",
    Options.text("source").optional ++ Options.text("type").optional ++
      Options.text("kind").optional ++ Options.text("ids").optional
  ).map { case (source, nodeType, kind, ids) => Cmd.AcceptAll(source, nodeType, kind, ids) }

  // --- event ---
  private val eventRecord = Command(
    "record",
    Options.text("actor").optional ++ Options.text("action") ++ Options.text("category") ++
      Options.text("target-type").optional ++
      Options.text("target-id").optional ++ Options.text("text").optional ++
      Options.text("payload-json").optional
  ).map { case (actor, action, category, ttype, tid, text, payload) =>
    Cmd.EventRecord(actor, action, category, ttype, tid, text, payload)
  }
  private val eventLog = Command(
    "log",
    Options.text("category").optional ++
      Options.text("since").optional ++ Options.text("until").optional ++
      Options.integer("limit").optional
  ).map { case (category, since, until, limit) =>
    Cmd.EventLog(category, since, until, limit.map(_.toInt))
  }
  private val eventSearch = Command(
    "search",
    Options.text("category").optional ++
      Options.text("since").optional ++ Options.integer("limit").optional,
    Args.text("query")
  ).map { case ((category, since, limit), q) =>
    Cmd.EventSearch(q, category, since, limit.map(_.toInt))
  }
  private val event = Command("event").subcommands(eventRecord, eventLog, eventSearch)

  // --- gmail ---
  private val gmailAuth = Command(
    "auth",
    Options.text("owner")
  ).map(owner => Cmd.GmailAuth(owner))

  private val gmailSync = Command(
    "sync",
    Options.text("owner") ++ Options.text("since").optional
  ).map { case (owner, since) => Cmd.GmailSync(owner, since) }

  private val gmail = Command("gmail").subcommands(gmailAuth, gmailSync)

  // --- inbox ---
  private val inboxList = Command(
    "list",
    Options.text("owner") ++ Options.text("status").optional ++ Options.integer("limit").optional ++ Options.text("order").optional
  ).map { case (owner, status, limit, order) => Cmd.InboxList(owner, status, limit.map(_.toInt), order) }

  private val inboxShow = Command("show", Args.text("id")).map(id => Cmd.InboxShow(id))

  // Download attachments for an inbox message to a local directory. Without
  // --attachment, every attachment is written; with it, just that one.
  private val inboxDownload = Command(
    "download",
    Options.text("out") ++ Options.text("attachment").optional,
    Args.text("id")
  ).map { case ((out, attachment), id) => Cmd.InboxDownload(id, out, attachment) }

  private val inboxSkip = Command("skip", Args.text("id")).map(id => Cmd.InboxSkip(id))

  private val inboxMarkTriaged = Command(
    "mark-triaged",
    Options.text("source-event-id").optional,
    Args.text("id")
  ).map { case (sourceEventId, id) => Cmd.InboxMarkTriaged(id, sourceEventId) }

  private val inbox = Command("inbox").subcommands(inboxList, inboxShow, inboxDownload, inboxSkip, inboxMarkTriaged)

  // --- calendar (read-only) ---
  // Defaults to the next `--days` (14) from now; --from/--to (ISO-8601) override.
  private val calendarAgenda = Command(
    "agenda",
    Options.text("owner") ++ Options.integer("days").optional ++ Options.text("from").optional ++ Options.text("to").optional
  ).map { case (owner, days, from, to) => Cmd.CalendarAgenda(owner, days.map(_.toInt), from, to) }

  private val calendar = Command("calendar").subcommands(calendarAgenda)

  // --- person (household members / graph person-nodes) ---
  private val personCreate = Command(
    "create",
    Options.text("id") ++ Options.text("display-name") ++
      Options.text("timezone") ++ Options.text("locale").optional
  ).map { case (id, name, tz, locale) => Cmd.PersonCreate(id, name, tz, locale) }

  private val personList = Command("list").map(_ => Cmd.PersonList)

  private val personSub = Command("person").subcommands(personCreate, personList)

  // --- top-level ---
  private val health = Command("health").map(_ => Cmd.Health)

  private val personCommand = Command("person").subcommands(
    health, personSub, commitment, memoryExt, approval, goal, entity, relationship, household,
    pending, acceptAll, event, gmail, inbox, calendar
  )

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

    case Cmd.PersonCreate(id, name, tz, locale) =>
      val body = jsonObj(
        "id"            -> id.toJson,
        "displayName"   -> name.toJson,
        "timezone"      -> tz.toJson,
        "defaultLocale" -> locale.toJson
      )
      HttpClient.post("/persons", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.PersonList =>
      HttpClient.get("/persons").flatMap(out => Console.printLine(out).orDie)

    case Cmd.CommitmentPropose(owner, text, source, evidence, due) =>
      val body = jsonObj(
        "ownerPersonId" -> owner.toJson,
        "text"          -> text.toJson,
        "source"        -> source.toJson,
        "evidence"      -> evidence.toJson,
        "dueAt"         -> due.toJson
      )
      HttpClient.post("/commitments/propose", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.CommitmentList(owner, status) =>
      HttpClient.get("/commitments", paramsMap("owner" -> owner, "status" -> status))
        .flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryPropose(person, kind, text, source, confidence, validFrom, validUntil) =>
      for {
        memKind <- parseMemoryKind(kind)
        body     = jsonObj(
                     "personId"   -> (Some(person): Option[String]).toJson,
                     "kind"       -> memKind.toJson,
                     "text"       -> text.toJson,
                     "source"     -> source.toJson,
                     "confidence" -> numOrNull(confidence),
                     "validFrom"  -> validFrom.toJson,
                     "validUntil" -> validUntil.toJson
                   )
        out     <- HttpClient.post("/memory/propose", body)
        _       <- Console.printLine(out).orDie
      } yield ()

    case Cmd.ApprovalRequest(actionType, payloadJson) =>
      val body = jsonObj(
        "requestedBy"      -> "\"agent\"",
        "requiredPersonId" -> "null",
        "actionType"       -> actionType.toJson,
        "payloadJson"      -> payloadJson.toJson
      )
      HttpClient.post("/approvals/request", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.GoalPropose(owner, title, outcome, evidenceRule, constraints, source) =>
      val body = jsonObj(
        "ownerPersonId"   -> owner.toJson,
        "title"           -> title.toJson,
        "outcome"         -> outcome.toJson,
        "evidenceRule"    -> evidenceRule.toJson,
        "constraintsJson" -> constraints.toJson,
        "source"          -> source.toJson
      )
      HttpClient.post("/goals", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.GoalList(owner, status) =>
      HttpClient.get("/goals", paramsMap("owner" -> owner, "status" -> status))
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

    case Cmd.MemorySearch(query, person, kind, asOf, limit) =>
      val params = paramsMap(
        "q"      -> Some(query),
        "person" -> person,
        "kind"   -> kind,
        "as_of"  -> asOf,
        "limit"  -> limit.map(_.toString)
      )
      HttpClient.get("/memory/search", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryContext(person, factLim, eventLim) =>
      val params = paramsMap(
        "person"      -> person,
        "fact_limit"  -> factLim.map(_.toString),
        "event_limit" -> eventLim.map(_.toString)
      )
      HttpClient.get("/memory/context", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryConflicts(person, kind, text) =>
      val params = paramsMap(
        "person" -> person,
        "kind"   -> Some(kind),
        "text"   -> Some(text)
      )
      HttpClient.get("/memory/conflicts", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryConsolidate(since) =>
      val body = jsonObj("since" -> since.toJson)
      HttpClient.post("/memory/consolidate", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryProfile(limit) =>
      HttpClient.get("/memory/profile", paramsMap("limit" -> limit.map(_.toString)))
        .flatMap(out => Console.printLine(out).orDie)

    case Cmd.EntityPropose(kind, name, attrs, source, confidence) =>
      val body = jsonObj(
        "kind"           -> kind.toJson,
        "name"           -> name.toJson,
        "attributesJson" -> attrs.toJson,
        "source"         -> source.toJson,
        "confidence"     -> numOrNull(confidence)
      )
      HttpClient.post("/entities/propose", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.EntityList(kind, status) =>
      HttpClient.get("/entities", paramsMap("kind" -> kind, "status" -> status))
        .flatMap(out => Console.printLine(out).orDie)

    case Cmd.EntityResolve(name) =>
      HttpClient.get("/entities", Map("name" -> name)).flatMap(out => Console.printLine(out).orDie)

    case Cmd.EntityAccept(id) =>
      HttpClient.post(s"/entities/$id/accept", "{}").flatMap(out => Console.printLine(out).orDie)

    case Cmd.EntityReject(id, reason) =>
      val body = jsonObj("reason" -> reason.toJson)
      HttpClient.post(s"/entities/$id/reject", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.EntitySupersede(newId, oldId) =>
      val body = jsonObj("newId" -> newId.toJson, "oldId" -> oldId.toJson)
      HttpClient.post("/entities/supersede", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.RelationshipPropose(from, fromKind, relType, to, toKind, source, confidence, note, validFrom, validUntil) =>
      val body = jsonObj(
        "fromId"     -> from.toJson,
        "fromKind"   -> fromKind.toJson,
        "relType"    -> relType.toJson,
        "toId"       -> to.toJson,
        "toKind"     -> toKind.toJson,
        "source"     -> source.toJson,
        "confidence" -> numOrNull(confidence),
        "note"       -> note.toJson,
        "validFrom"  -> validFrom.toJson,
        "validUntil" -> validUntil.toJson
      )
      HttpClient.post("/relationships/propose", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.RelationshipList(from, to, relType, status, asOf) =>
      val params = paramsMap(
        "from"   -> from,
        "to"     -> to,
        "type"   -> relType,
        "status" -> status,
        "as_of"  -> asOf
      )
      HttpClient.get("/relationships", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.RelationshipAccept(id) =>
      HttpClient.post(s"/relationships/$id/accept", "{}").flatMap(out => Console.printLine(out).orDie)

    case Cmd.RelationshipReject(id, reason) =>
      val body = jsonObj("reason" -> reason.toJson)
      HttpClient.post(s"/relationships/$id/reject", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.RelationshipSupersede(newId, oldId) =>
      val body = jsonObj("newId" -> newId.toJson, "oldId" -> oldId.toJson)
      HttpClient.post("/relationships/supersede", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.Household =>
      HttpClient.get("/household").flatMap(out => Console.printLine(out).orDie)

    case Cmd.MemoryList(person, kind, status) =>
      HttpClient.get("/memory", paramsMap("person" -> person, "kind" -> kind, "status" -> status))
        .flatMap(out => Console.printLine(out).orDie)

    case Cmd.Pending(source, nodeType, kind) =>
      HttpClient.get("/pending", paramsMap("source" -> source, "type" -> nodeType, "kind" -> kind))
        .flatMap(out => Console.printLine(out).orDie)

    case Cmd.AcceptAll(source, nodeType, kind, ids) =>
      val idList = ids.map(_.split(",").toList.map(_.trim).filter(_.nonEmpty)).getOrElse(Nil)
      val body = jsonObj(
        "source" -> source.toJson,
        "type"   -> nodeType.toJson,
        "kind"   -> kind.toJson,
        "ids"    -> idList.toJson
      )
      HttpClient.post("/accept-all", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.EventRecord(actor, action, category, ttype, tid, text, payload) =>
      val body = jsonObj(
        "actor"       -> actor.getOrElse("agent").toJson,
        "action"      -> action.toJson,
        "category"    -> category.toJson,
        "targetType"  -> ttype.toJson,
        "targetId"    -> tid.toJson,
        "text"        -> text.toJson,
        "payloadJson" -> payload.toJson
      )
      HttpClient.post("/events", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.EventLog(category, since, until, limit) =>
      val params = paramsMap(
        "category" -> category,
        "since"    -> since,
        "until"    -> until,
        "limit"    -> limit.map(_.toString)
      )
      HttpClient.get("/events", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.EventSearch(query, category, since, limit) =>
      val params = paramsMap(
        "q"        -> Some(query),
        "category" -> category,
        "since"    -> since,
        "limit"    -> limit.map(_.toString)
      )
      HttpClient.get("/events/search", params).flatMap(out => Console.printLine(out).orDie)

    case Cmd.GmailAuth(owner) =>
      for {
        raw     <- HttpClient.get("/gmail/auth-url", Map("owner" -> owner))
        url      = extractJsonString(raw, "url").getOrElse("")
        redirect = extractJsonString(raw, "redirectUri").getOrElse("http://localhost:8765/oauth/callback")
        port     = redirectUriPort(redirect)
        _       <- Console.printLine(s"Open this URL to authorize Gmail:\n$url").orDie
        _       <- OAuthCallback.openBrowser(url)
        _       <- Console.printLine(s"Waiting for OAuth callback on $redirect …").orDie
        code    <- OAuthCallback.waitForCode(port, "/oauth/callback")
        body     = jsonObj(
                     "ownerPersonId" -> owner.toJson,
                     "code"          -> code.toJson,
                     "redirectUri"   -> redirect.toJson
                   )
        out     <- HttpClient.post("/gmail/oauth/exchange", body)
        _       <- Console.printLine(out).orDie
      } yield ()

    case Cmd.GmailSync(owner, since) =>
      HttpClient.post("/gmail/sync", "{}", paramsMap("owner" -> Some(owner), "since" -> since))
        .flatMap(out => Console.printLine(out).orDie)

    case Cmd.InboxList(owner, status, limit, order) =>
      HttpClient.get("/inbox", paramsMap(
        "owner"  -> Some(owner),
        "status" -> status,
        "limit"  -> limit.map(_.toString),
        "order"  -> order
      )).flatMap(out => Console.printLine(out).orDie)

    case Cmd.InboxShow(id) =>
      HttpClient.get(s"/inbox/$id").flatMap(out => Console.printLine(out).orDie)

    case Cmd.InboxDownload(id, out, attachment) =>
      for {
        raw    <- HttpClient.get(s"/inbox/$id")
        msg    <- ZIO.fromEither(raw.fromJson[InboxMessage]).mapError(m => AgentError.DecodeFailed(s"inbox message: $m"))
        chosen  = attachment match {
                    case Some(aid) => msg.attachments.filter(_.attachmentId == aid)
                    case None      => msg.attachments
                  }
        _      <- ZIO.when(chosen.isEmpty)(ZIO.fail(AgentError.NotFound(
                    "attachment",
                    attachment.getOrElse(s"(no attachments on inbox message $id)")
                  )))
        written <- ZIO.foreach(chosen)(a => downloadAttachment(id, out, a))
        summary  = jsonObj(
                     "inboxId" -> id.toJson,
                     "out"     -> out.toJson,
                     "written" -> written.toJson
                   )
        _      <- Console.printLine(summary).orDie
      } yield ()

    case Cmd.InboxSkip(id) =>
      HttpClient.post(s"/inbox/$id/skip", "{}").flatMap(out => Console.printLine(out).orDie)

    case Cmd.InboxMarkTriaged(id, sourceEventId) =>
      val body = jsonObj("sourceEventId" -> sourceEventId.toJson)
      HttpClient.post(s"/inbox/$id/mark-triaged", body).flatMap(out => Console.printLine(out).orDie)

    case Cmd.CalendarAgenda(owner, days, from, to) =>
      HttpClient.get("/calendar/agenda", paramsMap(
        "owner" -> Some(owner),
        "days"  -> days.map(_.toString),
        "from"  -> from,
        "to"    -> to
      )).flatMap(out => Console.printLine(out).orDie)
  }

  /** Fetch one attachment's bytes (base64 in JSON) and write them under `outDir`,
   *  returning the absolute path written. */
  private def downloadAttachment(inboxId: String, outDir: String, a: InboxAttachment): IO[AgentError, String] =
    for {
      raw  <- HttpClient.get(s"/inbox/$inboxId/attachments/${a.attachmentId}")
      data <- ZIO.fromOption(extractJsonString(raw, "dataBase64"))
                .orElseFail(AgentError.DecodeFailed(s"attachment ${a.attachmentId}: missing dataBase64"))
      name  = extractJsonString(raw, "filename").filter(_.nonEmpty).getOrElse(a.filename)
      path <- writeAttachment(outDir, name, a.attachmentId, data)
    } yield path

  private def writeAttachment(outDir: String, filename: String, attachmentId: String, dataBase64: String): IO[AgentError, String] =
    ZIO.attemptBlocking {
      val safe   = sanitizeFilename(filename, attachmentId)
      val dir    = java.nio.file.Paths.get(outDir)
      java.nio.file.Files.createDirectories(dir)
      val target = dir.resolve(safe)
      val bytes  = java.util.Base64.getDecoder.decode(dataBase64)
      java.nio.file.Files.write(target, bytes)
      target.toAbsolutePath.toString
    }.mapError(AgentError.fromThrowable(s"writing attachment to $outDir"))

  /** Keep only the basename and strip anything that could escape the target
   *  directory; fall back to the attachment id when there is no usable name. */
  private def sanitizeFilename(filename: String, attachmentId: String): String = {
    val base    = filename.replace('\\', '/').split('/').lastOption.getOrElse("").trim
    val cleaned = base.replaceAll("[^A-Za-z0-9._-]", "_")
    if (cleaned.isEmpty || cleaned == "." || cleaned == "..")
      s"attachment-${attachmentId.take(12).replaceAll("[^A-Za-z0-9._-]", "_")}"
    else cleaned
  }

  private def jsonObj(fields: (String, String)*): String =
    fields.map { case (k, v) => s""""$k":$v""" }.mkString("{", ",", "}")

  /** A confidence/decimal as a raw JSON number, or literal `null` when absent. */
  private def numOrNull(d: Option[BigDecimal]): String =
    d.map(_.toString).getOrElse("null")

  private def paramsMap(pairs: (String, Option[String])*): Map[String, String] =
    pairs.collect { case (k, Some(v)) => k -> v }.toMap

  private def parseMemoryKind(s: String): IO[AgentError, MemoryKind] =
    ZIO.fromEither(MemoryKind.fromString(s)).mapError(AgentError.Validation)

  private def parseGoalStatus(s: String): IO[AgentError, GoalStatus] =
    ZIO.fromEither(GoalStatus.fromString(s)).mapError(AgentError.Validation)

  private def extractJsonString(json: String, field: String): Option[String] =
    json.fromJson[Json].toOption.flatMap {
      case Json.Obj(fields) => fields.collectFirst { case (k, Json.Str(v)) if k == field => v }
      case _                => None
    }

  private def redirectUriPort(redirectUri: String): Int =
    try {
      val uri = URI.create(redirectUri)
      if (uri.getPort > 0) uri.getPort else if (uri.getScheme == "https") 443 else 80
    } catch { case _: Throwable => 8765 }
}
