package dev.freskog.agent.person.service

import dev.freskog.agent.common.{Scope => PersonScope, _}
import dev.freskog.agent.common.JsonCodecs._
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.persistence._

import zio._
import zio.json._

import java.time.Instant
import java.util.UUID

trait PersonService {
  def createPerson(req: CreatePersonRequest): IO[AgentError, Person]
  def listPersons: IO[AgentError, List[Person]]
  def createScope(req: CreateScopeRequest): IO[AgentError, PersonScope]
  def listScopes: IO[AgentError, List[PersonScope]]
  def createScopeRole(req: CreateScopeRoleRequest): IO[AgentError, PersonScopeRole]
  def proposeCommitment(req: ProposeCommitmentRequest): IO[AgentError, Commitment]
  def listCommitments(owner: Option[PersonId], scope: Option[ScopeId], status: Option[String]): IO[AgentError, List[Commitment]]
  def proposeMemory(req: ProposeMemoryRequest): IO[AgentError, MemoryItem]
  def listMemory(personId: Option[PersonId], scopeId: Option[ScopeId]): IO[AgentError, List[MemoryItem]]
  def requestApproval(req: RequestApprovalRequest): IO[AgentError, Approval]
  def listApprovals(scopeId: Option[ScopeId], status: Option[String]): IO[AgentError, List[Approval]]
  def proposeGoal(req: ProposeGoalRequest): IO[AgentError, Goal]
  def listGoals(owner: Option[PersonId], scope: Option[ScopeId], status: Option[String]): IO[AgentError, List[Goal]]
  def getGoal(id: GoalId): IO[AgentError, Option[GoalWithEvidence]]
  def updateGoalStatus(id: GoalId, req: UpdateGoalStatusRequest): IO[AgentError, Goal]
  def appendGoalEvidence(id: GoalId, req: AppendGoalEvidenceRequest): IO[AgentError, GoalEvidence]

  // Memory lifecycle
  def acceptMemory(id: MemoryId): IO[AgentError, MemoryItem]
  def rejectMemory(id: MemoryId, reason: Option[String]): IO[AgentError, MemoryItem]
  def archiveMemory(id: MemoryId): IO[AgentError, MemoryItem]
  def supersedeMemory(newId: MemoryId, oldId: MemoryId): IO[AgentError, MemoryItem]

  // Memory recall
  def searchMemory(query: String, scopeId: Option[ScopeId], personId: Option[PersonId], kind: Option[String], asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryHit]]
  def contextBundle(scopeId: Option[ScopeId], personId: Option[PersonId], factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle]
  def findConflicts(scopeId: Option[ScopeId], personId: Option[PersonId], kind: String, text: String): IO[AgentError, List[MemoryItem]]

  // Generic event log
  def logEvent(req: LogEventRequest): IO[AgentError, AuditEvent]
  def listEvents(scopeId: Option[ScopeId], category: Option[String], since: Option[Instant], until: Option[Instant], limit: Int): IO[AgentError, List[AuditEvent]]
  def searchEvents(query: String, scopeId: Option[ScopeId], category: Option[String], since: Option[Instant], limit: Int): IO[AgentError, List[EventHit]]

  // Consolidation
  def consolidateMemory(scopeId: ScopeId, since: Option[Instant]): IO[AgentError, List[MemoryItem]]
}

object PersonService {

  /** Time-decay half-life used by recency ranking on facts (90 days). */
  private val RecencyHalfLifeDays: Double = 90.0
  private val DefaultConfidence: Double   = 0.5

  def live(
    personRepo: PersonRepo,
    scopeRepo: ScopeRepo,
    scopeRoleRepo: ScopeRoleRepo,
    commitmentRepo: CommitmentRepo,
    memoryRepo: MemoryRepo,
    approvalRepo: ApprovalRepo,
    auditRepo: AuditRepo,
    goalRepo: GoalRepo,
    goalEvidenceRepo: GoalEvidenceRepo
  ): PersonService = new PersonService {

    // --- Persons / Scopes / Roles ---

    def createPerson(req: CreatePersonRequest): IO[AgentError, Person] = {
      val p = Person(req.id, req.displayName, req.timezone, req.defaultLocale, active = true)
      personRepo.create(p).as(p)
    }

    val listPersons: IO[AgentError, List[Person]] = personRepo.findAll

    def createScope(req: CreateScopeRequest): IO[AgentError, PersonScope] = {
      val s = PersonScope(req.id, req.name, req.ownerPersonId, req.kind)
      scopeRepo.create(s).as(s)
    }

    val listScopes: IO[AgentError, List[PersonScope]] = scopeRepo.findAll

    def createScopeRole(req: CreateScopeRoleRequest): IO[AgentError, PersonScopeRole] = {
      val r = PersonScopeRole(req.personId, req.scopeId, req.role)
      scopeRoleRepo.create(r).as(r)
    }

    // --- Commitments ---

    def proposeCommitment(req: ProposeCommitmentRequest): IO[AgentError, Commitment] =
      for {
        now <- Clock.instant
        c    = Commitment(CommitmentId(newUuid), req.ownerPersonId, req.scopeId,
                          CommitmentStatus.Proposed, req.text, req.source, req.evidence,
                          req.dueAt, now, now)
        _   <- commitmentRepo.create(c)
        _   <- audit("commitment.propose", "commitment", Some(c.id.value), Some(req.scopeId), now)
      } yield c

    def listCommitments(owner: Option[PersonId], scope: Option[ScopeId], status: Option[String]): IO[AgentError, List[Commitment]] =
      commitmentRepo.findAll(owner, scope, status)

    // --- Memory ---

    def proposeMemory(req: ProposeMemoryRequest): IO[AgentError, MemoryItem] =
      for {
        now <- Clock.instant
        m    = MemoryItem(
                 id = MemoryId(newUuid),
                 personId = req.personId, scopeId = req.scopeId,
                 status = MemoryStatus.Proposed, kind = req.kind,
                 text = req.text, source = req.source, confidence = req.confidence,
                 createdAt = now, updatedAt = now,
                 validFrom = req.validFrom, validUntil = req.validUntil,
                 originEventId = req.originEventId
               )
        _   <- memoryRepo.create(m)
        _   <- audit("memory.propose", "memory_item", Some(m.id.value), req.scopeId, now)
      } yield m

    def listMemory(personId: Option[PersonId], scopeId: Option[ScopeId]): IO[AgentError, List[MemoryItem]] =
      memoryRepo.findAll(personId, scopeId)

    def acceptMemory(id: MemoryId): IO[AgentError, MemoryItem]              = transitionMemory(id, MemoryStatus.Accepted, "memory.accept", None)
    def rejectMemory(id: MemoryId, reason: Option[String]): IO[AgentError, MemoryItem] =
      transitionMemory(id, MemoryStatus.Rejected, "memory.reject", reason)
    def archiveMemory(id: MemoryId): IO[AgentError, MemoryItem]             = transitionMemory(id, MemoryStatus.Archived, "memory.archive", None)

    private def transitionMemory(id: MemoryId, to: MemoryStatus, action: String, reason: Option[String]): IO[AgentError, MemoryItem] =
      for {
        existing <- requireMemory(id)
        now      <- Clock.instant
        _        <- memoryRepo.updateStatus(id, to, now)
        _        <- auditPayload(action, "memory_item", Some(id.value), existing.scopeId, now, StatusChangePayload(reason))
        updated  <- requireMemory(id)
      } yield updated

    def supersedeMemory(newId: MemoryId, oldId: MemoryId): IO[AgentError, MemoryItem] =
      for {
        newItem <- requireMemory(newId)
        _       <- requireMemory(oldId)
        now     <- Clock.instant
        _       <- memoryRepo.setSupersededBy(oldId, newId, now)
        _       <- auditPayload("memory.supersede", "memory_item", Some(oldId.value), newItem.scopeId, now, SupersedePayload(newId))
        result  <- requireMemory(oldId)
      } yield result

    private def requireMemory(id: MemoryId): IO[AgentError, MemoryItem] =
      memoryRepo.findById(id).someOrFail(AgentError.NotFound("memory_item", id.value))

    // --- Memory recall ---

    def searchMemory(query: String, scopeId: Option[ScopeId], personId: Option[PersonId], kind: Option[String], asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryHit]] = {
      val sanitized = sanitizeFtsQuery(query)
      if (sanitized.isEmpty) ZIO.succeed(Nil)
      else
        for {
          raw <- memoryRepo.searchFts(sanitized, scopeId, personId, kind, Some("accepted"), asOf, math.max(limit * 5, 50))
          now <- Clock.instant
        } yield rerank(raw, now).take(limit)
    }

    def contextBundle(scopeId: Option[ScopeId], personId: Option[PersonId], factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle] =
      for {
        facts  <- recentFacts(scopeId, personId, factLimit)
        events <- recentEvents(scopeId, eventLimit)
      } yield ContextBundle(facts, events)

    private def recentFacts(scopeId: Option[ScopeId], personId: Option[PersonId], factLimit: Int): IO[AgentError, List[MemoryHit]] =
      for {
        raw <- memoryRepo.listAccepted(scopeId, personId, None, math.max(factLimit * 3, 30))
        now <- Clock.instant
      } yield rerank(raw, now).take(factLimit)

    private def recentEvents(scopeId: Option[ScopeId], eventLimit: Int): IO[AgentError, List[AuditEvent]] =
      ZIO
        .foreach(List(EventCategory.Observation, EventCategory.Decision, EventCategory.SessionNote))(cat =>
          auditRepo.list(scopeId, Some(cat), None, None, eventLimit)
        )
        .map(_.flatten.sortBy(_.createdAt).reverse.take(eventLimit))

    def findConflicts(scopeId: Option[ScopeId], personId: Option[PersonId], kind: String, text: String): IO[AgentError, List[MemoryItem]] = {
      val sanitized = sanitizeFtsQuery(text)
      if (sanitized.isEmpty) ZIO.succeed(Nil)
      else memoryRepo.searchFts(sanitized, scopeId, personId, Some(kind), Some("accepted"), None, 20)
    }

    private def rerank(items: List[MemoryItem], now: Instant): List[MemoryHit] = {
      val scored = items.map(m => MemoryHit(m, scoreFor(m, now)))
      // Deterministic order even with tied scores — break ties by id.
      scored.sortBy(h => (-h.score, h.item.id.value))
    }

    private def scoreFor(m: MemoryItem, now: Instant): Double = {
      val ageDays = (now.toEpochMilli - m.createdAt.toEpochMilli).toDouble / (1000.0 * 60 * 60 * 24)
      val recency = math.exp(-ageDays / RecencyHalfLifeDays)
      val conf    = m.confidence.getOrElse(DefaultConfidence)
      conf * recency
    }

    // --- Approvals ---

    def requestApproval(req: RequestApprovalRequest): IO[AgentError, Approval] =
      for {
        now <- Clock.instant
        a    = Approval(ApprovalId(newUuid), req.requestedBy, req.requiredPersonId, req.scopeId,
                        req.actionType, req.payloadJson, ApprovalStatus.Requested, now, None)
        _   <- approvalRepo.create(a)
        _   <- auditPayloadRaw(
                 actor = req.requestedBy, action = "approval.request",
                 targetType = "approval", targetId = Some(a.id.value), scopeId = req.scopeId,
                 now = now, payloadJson = req.payloadJson
               )
      } yield a

    def listApprovals(scopeId: Option[ScopeId], status: Option[String]): IO[AgentError, List[Approval]] =
      approvalRepo.findAll(scopeId, status)

    // --- Goals ---

    def proposeGoal(req: ProposeGoalRequest): IO[AgentError, Goal] =
      for {
        now <- Clock.instant
        g    = Goal(GoalId(newUuid), req.ownerPersonId, req.scopeId, req.title, req.outcome,
                    req.evidenceRule, req.constraintsJson, GoalStatus.Open, None, req.source, now, now)
        _   <- goalRepo.create(g)
        _   <- audit("goal.propose", "goal", Some(g.id.value), Some(req.scopeId), now)
      } yield g

    def listGoals(owner: Option[PersonId], scope: Option[ScopeId], status: Option[String]): IO[AgentError, List[Goal]] =
      goalRepo.findAll(owner, scope, status)

    def getGoal(id: GoalId): IO[AgentError, Option[GoalWithEvidence]] =
      goalRepo.findById(id).flatMap {
        case None       => ZIO.succeed(None)
        case Some(goal) => goalEvidenceRepo.findByGoal(id).map(ev => Some(GoalWithEvidence(goal, ev)))
      }

    def updateGoalStatus(id: GoalId, req: UpdateGoalStatusRequest): IO[AgentError, Goal] =
      for {
        existing <- requireGoal(id)
        now      <- Clock.instant
        _        <- goalRepo.updateStatus(id, req.status, req.blockedReason, now)
        _        <- auditPayload(
                      action = s"goal.status.${GoalStatus.asString(req.status)}",
                      targetType = "goal", targetId = Some(id.value),
                      scopeId = Some(existing.scopeId), now = now,
                      payload = StatusChangePayload(req.blockedReason)
                    )
        updated  <- requireGoal(id)
      } yield updated

    def appendGoalEvidence(id: GoalId, req: AppendGoalEvidenceRequest): IO[AgentError, GoalEvidence] =
      for {
        goal <- requireGoal(id)
        now  <- Clock.instant
        ev    = GoalEvidence(GoalEvidenceId(newUuid), id, req.kind, req.ref, req.note, now)
        _    <- goalEvidenceRepo.create(ev)
        _    <- auditPayload("goal.evidence.append", "goal", Some(id.value), Some(goal.scopeId), now,
                             GoalEvidencePayload(req.kind, req.ref))
      } yield ev

    private def requireGoal(id: GoalId): IO[AgentError, Goal] =
      goalRepo.findById(id).someOrFail(AgentError.NotFound("goal", id.value))

    // --- Generic event log ---

    def logEvent(req: LogEventRequest): IO[AgentError, AuditEvent] =
      for {
        now <- Clock.instant
        ev   = AuditEvent(
                 id = EventId(newUuid),
                 actor = req.actor, action = req.action,
                 category = if (EventCategory.All.contains(req.category)) req.category else EventCategory.Observation,
                 targetType = req.targetType.getOrElse("event"),
                 targetId = req.targetId, scopeId = req.scopeId,
                 text = req.text, payloadJson = req.payloadJson.getOrElse("{}"),
                 createdAt = now
               )
        _   <- auditRepo.create(ev)
      } yield ev

    def listEvents(scopeId: Option[ScopeId], category: Option[String], since: Option[Instant], until: Option[Instant], limit: Int): IO[AgentError, List[AuditEvent]] =
      auditRepo.list(scopeId, category, since, until, limit)

    def searchEvents(query: String, scopeId: Option[ScopeId], category: Option[String], since: Option[Instant], limit: Int): IO[AgentError, List[EventHit]] = {
      val sanitized = sanitizeFtsQuery(query)
      if (sanitized.isEmpty) ZIO.succeed(Nil)
      else
        auditRepo.searchFts(sanitized, scopeId, category, since, limit).map(eventsToHits(_, limit))
    }

    private def eventsToHits(events: List[AuditEvent], limit: Int): List[EventHit] =
      events.zipWithIndex.map { case (e, i) => EventHit(e, (limit - i).toDouble) }

    // --- Consolidation ---

    def consolidateMemory(scopeId: ScopeId, since: Option[Instant]): IO[AgentError, List[MemoryItem]] =
      for {
        notes   <- auditRepo.list(Some(scopeId), Some(EventCategory.SessionNote), since, None, 200)
        obs     <- auditRepo.list(Some(scopeId), Some(EventCategory.Observation), since, None, 200)
        all      = notes ++ obs
        already <- memoryRepo.findEventsWithMemory(all.map(_.id))
        fresh    = all.filterNot(e => already.contains(e.id))
        now     <- Clock.instant
        items   <- ZIO.foreach(fresh)(e => consolidateOne(scopeId, e, now))
        _       <- ZIO.when(items.nonEmpty)(
                     auditPayload("memory.consolidate", "scope", Some(scopeId.value), Some(scopeId), now,
                                  ConsolidatePayload(items.size))
                   )
      } yield items

    private def consolidateOne(scopeId: ScopeId, e: AuditEvent, now: Instant): IO[AgentError, MemoryItem] = {
      val item = MemoryItem(
        id = MemoryId(newUuid),
        personId = None, scopeId = Some(scopeId),
        status = MemoryStatus.Proposed, kind = MemoryKind.Fact,
        text = e.text.getOrElse(e.payloadJson),
        source = s"event:${e.id.value}", confidence = Some(DefaultConfidence),
        createdAt = now, updatedAt = now,
        originEventId = Some(e.id)
      )
      memoryRepo.create(item).as(item)
    }

    // --- Audit helpers ---

    /** Audit event with no payload (empty JSON object). */
    private def audit(action: String, targetType: String, targetId: Option[String], scopeId: Option[ScopeId], now: Instant): IO[AgentError, Unit] =
      writeEvent(actor = "agent", action = action, targetType = targetType, targetId = targetId,
                 scopeId = scopeId, now = now, payloadJson = "{}")

    /** Audit event with a structured payload, encoded via the supplied codec. */
    private def auditPayload[P: JsonEncoder](action: String, targetType: String, targetId: Option[String], scopeId: Option[ScopeId], now: Instant, payload: P): IO[AgentError, Unit] =
      writeEvent(actor = "agent", action = action, targetType = targetType, targetId = targetId,
                 scopeId = scopeId, now = now, payloadJson = payload.toJson)

    /** Escape hatch for audit events whose payload arrives as opaque JSON
     *  from the caller (approval payloads, mostly). */
    private def auditPayloadRaw(actor: String, action: String, targetType: String, targetId: Option[String], scopeId: Option[ScopeId], now: Instant, payloadJson: String): IO[AgentError, Unit] =
      writeEvent(actor = actor, action = action, targetType = targetType, targetId = targetId,
                 scopeId = scopeId, now = now, payloadJson = payloadJson)

    private def writeEvent(actor: String, action: String, targetType: String, targetId: Option[String], scopeId: Option[ScopeId], now: Instant, payloadJson: String): IO[AgentError, Unit] =
      auditRepo.create(AuditEvent(
        id = EventId(newUuid), actor = actor, action = action, category = EventCategory.State,
        targetType = targetType, targetId = targetId, scopeId = scopeId,
        text = None, payloadJson = payloadJson, createdAt = now
      ))

    // --- Misc helpers ---

    private def newUuid: String = UUID.randomUUID().toString

    private def sanitizeFtsQuery(raw: String): String = {
      val cleaned = raw.map(c => if (c.isLetterOrDigit) c else ' ')
      val tokens  = cleaned.split("\\s+").iterator.filter(_.length >= 2).toList
      if (tokens.isEmpty) "" else tokens.map(_ + "*").mkString(" OR ")
    }
  }
}
