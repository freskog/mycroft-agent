package dev.freskog.agent.person.service

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.persistence._
import dev.freskog.agent.person.gmail._
import dev.freskog.agent.person.calendar._
import dev.freskog.agent.person.tasks._
import dev.freskog.agent.person.delivery._

import zio._
import zio.json._
import zio.json.ast.Json

import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration, Instant}
import java.util.UUID

trait PersonService {
  def createPerson(req: CreatePersonRequest): IO[AgentError, Person]
  def updatePerson(id: PersonId, req: UpdatePersonRequest): IO[AgentError, Person]
  def listPersons: IO[AgentError, List[Person]]
  def proposeCommitment(req: ProposeCommitmentRequest): IO[AgentError, Commitment]
  def listCommitments(owner: Option[PersonId], status: Option[String]): IO[AgentError, List[Commitment]]
  def updateCommitmentStatus(id: CommitmentId, status: CommitmentStatus, reason: Option[String]): IO[AgentError, Commitment]
  def proposeMemory(req: ProposeMemoryRequest): IO[AgentError, MemoryItem]
  def listMemory(personId: Option[PersonId], status: Option[String], kind: Option[String]): IO[AgentError, List[MemoryItem]]
  def requestApproval(req: RequestApprovalRequest): IO[AgentError, Approval]
  def listApprovals(status: Option[String]): IO[AgentError, List[Approval]]
  def getApproval(id: ApprovalId): IO[AgentError, Option[Approval]]
  /** Mint a fresh one-time decision code for a pending approval and return the
   *  plaintext (only its hash is stored). Served on the PRIVATE interface only —
   *  the agent cannot reach it, so it never obtains a code. Reissues (invalidating
   *  any prior code) so the latest notification always carries a working code. */
  def issueDecisionCode(id: ApprovalId): IO[AgentError, String]
  /** A human approves or rejects a pending approval. On approve the privileged
   *  action executes server-side, the result is recorded, and an `executed` event
   *  is emitted. Idempotent — re-approving an already-executed approval returns the
   *  stored result. This is the trusted human action — never the agent. */
  def decideApproval(id: ApprovalId, req: DecideApprovalRequest): IO[AgentError, Approval]
  def proposeGoal(req: ProposeGoalRequest): IO[AgentError, Goal]
  def listGoals(owner: Option[PersonId], status: Option[String]): IO[AgentError, List[Goal]]
  def getGoal(id: GoalId): IO[AgentError, Option[GoalWithEvidence]]
  def updateGoalStatus(id: GoalId, req: UpdateGoalStatusRequest): IO[AgentError, Goal]
  def appendGoalEvidence(id: GoalId, req: AppendGoalEvidenceRequest): IO[AgentError, GoalEvidence]

  // Memory lifecycle (gateless: facts are written accepted; these are corrections)
  def rejectMemory(id: MemoryId, reason: Option[String]): IO[AgentError, MemoryItem]
  def archiveMemory(id: MemoryId): IO[AgentError, MemoryItem]
  def supersedeMemory(newId: MemoryId, oldId: MemoryId): IO[AgentError, MemoryItem]

  // Memory recall
  def searchMemory(query: String, personId: Option[PersonId], kind: Option[String], asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryHit]]
  def contextBundle(personId: Option[PersonId], factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle]
  def findConflicts(personId: Option[PersonId], kind: String, text: String): IO[AgentError, List[MemoryItem]]
  /** Pinned owner/household profile facts: accepted, currently-valid,
   *  non-superseded facts sourced from onboarding. Non-decaying — injected
   *  into every turn regardless of recency. */
  def profileFacts(limit: Int): IO[AgentError, List[MemoryItem]]

  // Household graph: entities + relationships (gateless write; reject/supersede correct)
  def proposeEntity(req: ProposeEntityRequest): IO[AgentError, Entity]
  def rejectEntity(id: EntityId, reason: Option[String]): IO[AgentError, Entity]
  def supersedeEntity(newId: EntityId, oldId: EntityId): IO[AgentError, Entity]
  def listEntities(kind: Option[String], status: Option[String]): IO[AgentError, List[Entity]]
  /** Entity resolution: candidates matching a name (substring, accepted only). */
  def resolveEntities(name: String): IO[AgentError, List[Entity]]

  def proposeRelationship(req: ProposeRelationshipRequest): IO[AgentError, Relationship]
  def rejectRelationship(id: RelationshipId, reason: Option[String]): IO[AgentError, Relationship]
  def supersedeRelationship(newId: RelationshipId, oldId: RelationshipId): IO[AgentError, Relationship]
  def listRelationships(fromId: Option[String], toId: Option[String], relType: Option[String], status: Option[String], asOf: Option[Instant]): IO[AgentError, List[Relationship]]
  /** Accepted, currently-active household graph for context injection. */
  def household: IO[AgentError, HouseholdGraph]

  // Generic event log
  def logEvent(req: LogEventRequest): IO[AgentError, AuditEvent]
  def listEvents(category: Option[String], since: Option[Instant], until: Option[Instant], limit: Int): IO[AgentError, List[AuditEvent]]
  def searchEvents(query: String, category: Option[String], since: Option[Instant], limit: Int): IO[AgentError, List[EventHit]]

  // Consolidation
  def consolidateMemory(since: Option[Instant]): IO[AgentError, List[MemoryItem]]

  // Channels & messages (mycroft conversation state)
  def createChannel(req: CreateChannelRequest): IO[AgentError, ChannelWithMembers]
  def listChannels: IO[AgentError, List[Channel]]
  def getChannel(id: ChannelId): IO[AgentError, Option[ChannelWithMembers]]
  def addChannelMember(id: ChannelId, personId: PersonId): IO[AgentError, ChannelWithMembers]
  def appendMessage(req: AppendMessageRequest): IO[AgentError, Message]
  def listMessages(channelId: ChannelId, since: Option[Instant], limit: Int): IO[AgentError, List[Message]]

  // Gmail / inbox ingestion
  def gmailAuthUrl(ownerPersonId: PersonId): IO[AgentError, GmailAuthUrlResponse]
  def gmailOAuthExchange(req: GmailOAuthExchangeRequest): IO[AgentError, GmailCredentialSummary]
  /** Consent URL for the dedicated send-only sender account (briefings for this
   *  owner are sent as it, not the owner). Operator runs this once, signing in as
   *  that account. Keyed per owner so the credentials FK stays valid. */
  def senderAuthUrl(ownerPersonId: PersonId): IO[AgentError, GmailAuthUrlResponse]
  def senderOAuthExchange(ownerPersonId: PersonId, code: String): IO[AgentError, GmailCredentialSummary]
  def gmailSync(ownerPersonId: PersonId, since: Option[Instant]): IO[AgentError, GmailSyncResult]
  def listInbox(ownerPersonId: PersonId, status: Option[String], limit: Int, oldestFirst: Boolean = false): IO[AgentError, List[InboxSummary]]
  def getInbox(id: InboxMessageId): IO[AgentError, Option[InboxMessage]]
  def downloadAttachment(id: InboxMessageId, attachmentId: String): IO[AgentError, AttachmentDownload]
  def skipInbox(id: InboxMessageId): IO[AgentError, InboxMessage]
  def markInboxTriaged(id: InboxMessageId, sourceEventId: Option[EventId]): IO[AgentError, InboxMessage]

  // Calendar
  def calendarAgenda(ownerPersonId: PersonId, timeMin: Instant, timeMax: Instant): IO[AgentError, List[CalendarEvent]]
  def listCalendars(ownerPersonId: PersonId): IO[AgentError, List[CalendarSummary]]
  /** Create a calendar event directly (no approval): `[M]`-marked and idempotent
   *  (dedup on the agent's `source` key, else on summary+start). Reversible in
   *  Google; made safe by the marker + dedup, not a gate. */
  def createCalendarEventDirect(req: CalendarCreateEventRequest): IO[AgentError, CalendarEvent]

  /** Reconcile the owner's commitments with Google Tasks (projection + bounded
   *  sync-back). Infrastructure (poller-driven), not a gated action. */
  def syncTasks(ownerPersonId: PersonId): IO[AgentError, TaskSyncResult]

  /** Mirror the owner's Google Calendar into the local event store (upsert live
   *  events, mark vanished ones cancelled) so a Google-side change reaches the
   *  substrate. Poller-driven infrastructure. */
  def syncCalendar(ownerPersonId: PersonId): IO[AgentError, CalendarSyncResult]

  // Daily briefing (agent composes via submit; person-service delivers — no agent egress)
  /** Store the agent-composed briefing and deliver it on the owner's channel. */
  def submitBriefing(req: SubmitBriefingRequest): IO[AgentError, Briefing]
  /** Re-attempt delivery of any still-`pending` briefings for the owner. */
  def deliverPending(ownerPersonId: PersonId): IO[AgentError, Int]
  /** The daily run: sync calendar+tasks (fresh data), then trigger the agent to
   *  compose (once/day). Idempotent per owner per day. */
  def runDailyBriefing(ownerPersonId: PersonId): IO[AgentError, Unit]
}

object PersonService {

  /** Time-decay half-life used by recency ranking on facts (90 days). */
  private val RecencyHalfLifeDays: Double = 90.0
  private val DefaultConfidence: Double   = 0.5

  def live(
    personRepo: PersonRepo,
    commitmentRepo: CommitmentRepo,
    memoryRepo: MemoryRepo,
    approvalRepo: ApprovalRepo,
    auditRepo: AuditRepo,
    goalRepo: GoalRepo,
    goalEvidenceRepo: GoalEvidenceRepo,
    entityRepo: EntityRepo,
    relationshipRepo: RelationshipRepo,
    channelRepo: ChannelRepo,
    channelMemberRepo: ChannelMemberRepo,
    messageRepo: MessageRepo,
    credentialRepo: CredentialRepo,
    inboxRepo: InboxMessageRepo,
    calendarEventRepo: CalendarEventRepo,
    briefingRepo: BriefingRepo,
    approvalHub: Hub[ApprovalEvent],
    decisionCodeTtl: Duration = Duration.ofHours(48)
  ): PersonService = new PersonService {

    // --- Persons ---

    def createPerson(req: CreatePersonRequest): IO[AgentError, Person] = {
      val p = Person(req.id, req.displayName, req.timezone, req.defaultLocale, active = true)
      personRepo.create(p).as(p)
    }

    /** Partial, gateless update of an existing person's mutable metadata. */
    def updatePerson(id: PersonId, req: UpdatePersonRequest): IO[AgentError, Person] =
      for {
        existing <- personRepo.findById(id).someOrFail(AgentError.NotFound("person", id.value))
        updated   = existing.copy(
                      displayName   = req.displayName.getOrElse(existing.displayName),
                      timezone      = req.timezone.getOrElse(existing.timezone),
                      defaultLocale = req.defaultLocale.orElse(existing.defaultLocale)
                    )
        _        <- personRepo.update(updated)
        now      <- Clock.instant
        _        <- audit("person.update", "person", Some(id.value), now)
      } yield updated

    val listPersons: IO[AgentError, List[Person]] = personRepo.findAll

    private def toInboxSummary(m: InboxMessage): InboxSummary =
      InboxSummary(m.id, m.fromAddr, m.subject, m.receivedAt, m.triageStatus, m.attachments.size, m.threadId)

    // --- Commitments ---

    /** Propose-by-source is idempotent: re-proposing the same external source
     *  (e.g. `email:gmail-msg-X`) updates the existing item instead of creating a
     *  duplicate. Dedup is a server-side tool guarantee, so callers never need to
     *  check-before-propose. Generic sources (no namespace, e.g. `chat`) always
     *  create a fresh item. */
    def proposeCommitment(req: ProposeCommitmentRequest): IO[AgentError, Commitment] =
      for {
        now      <- Clock.instant
        existing <- if (isDedupSource(req.source)) commitmentRepo.findBySource(req.ownerPersonId, req.source)
                    else ZIO.none
        result   <- existing match {
                      case Some(c) =>
                        commitmentRepo.updateContent(c.id, req.text, req.evidence, req.dueAt, now) *>
                          audit("commitment.propose.update", "commitment", Some(c.id.value), now)
                            .as(c.copy(text = req.text, evidence = req.evidence, dueAt = req.dueAt, updatedAt = now))
                      case None =>
                        // Gateless: a commitment is written live as `Open` (tracked) and
                        // is reversible (cancel/ignore/done). `Open` means tracked, NOT
                        // that the person has agreed to it.
                        val c = Commitment(CommitmentId(newUuid), req.ownerPersonId,
                                           CommitmentStatus.Open, req.text, req.source, req.evidence,
                                           req.dueAt, now, now)
                        commitmentRepo.create(c) *>
                          audit("commitment.record", "commitment", Some(c.id.value), now).as(c)
                    }
      } yield result

    def listCommitments(owner: Option[PersonId], status: Option[String]): IO[AgentError, List[Commitment]] =
      commitmentRepo.findAll(owner, status)

    def updateCommitmentStatus(id: CommitmentId, status: CommitmentStatus, reason: Option[String]): IO[AgentError, Commitment] =
      for {
        _       <- commitmentRepo.findById(id).someOrFail(AgentError.NotFound("commitment", id.value))
        now     <- Clock.instant
        _       <- commitmentRepo.updateStatus(id, status, now)
        _       <- auditPayload(s"commitment.status.${CommitmentStatus.asString(status)}", "commitment", Some(id.value), now, StatusChangePayload(reason))
        updated <- commitmentRepo.findById(id).someOrFail(AgentError.NotFound("commitment", id.value))
      } yield updated

    // --- Memory ---

    def proposeMemory(req: ProposeMemoryRequest): IO[AgentError, MemoryItem] =
      for {
        now <- Clock.instant
        m    = MemoryItem(
                 id = MemoryId(newUuid),
                 personId = req.personId,
                 status = MemoryStatus.Accepted, kind = req.kind,
                 text = req.text, source = req.source, confidence = req.confidence,
                 createdAt = now, updatedAt = now,
                 validFrom = req.validFrom, validUntil = req.validUntil,
                 originEventId = req.originEventId,
                 trust = req.trust.getOrElse(TrustLevel.AgentInference),
                 sender = req.sender
               )
        _   <- memoryRepo.create(m)
        _   <- audit("memory.propose", "memory_item", Some(m.id.value), now)
      } yield m

    def listMemory(personId: Option[PersonId], status: Option[String], kind: Option[String]): IO[AgentError, List[MemoryItem]] =
      memoryRepo.findAll(personId, status, kind)

    def rejectMemory(id: MemoryId, reason: Option[String]): IO[AgentError, MemoryItem] =
      transitionMemory(id, MemoryStatus.Rejected, "memory.reject", reason)
    def archiveMemory(id: MemoryId): IO[AgentError, MemoryItem]             = transitionMemory(id, MemoryStatus.Archived, "memory.archive", None)

    private def transitionMemory(id: MemoryId, to: MemoryStatus, action: String, reason: Option[String]): IO[AgentError, MemoryItem] =
      for {
        _        <- requireMemory(id)
        now      <- Clock.instant
        _        <- memoryRepo.updateStatus(id, to, now)
        _        <- auditPayload(action, "memory_item", Some(id.value), now, StatusChangePayload(reason))
        updated  <- requireMemory(id)
      } yield updated

    def supersedeMemory(newId: MemoryId, oldId: MemoryId): IO[AgentError, MemoryItem] =
      for {
        _       <- requireMemory(newId)
        _       <- requireMemory(oldId)
        now     <- Clock.instant
        _       <- memoryRepo.setSupersededBy(oldId, newId, now)
        _       <- auditPayload("memory.supersede", "memory_item", Some(oldId.value), now, SupersedePayload(newId))
        result  <- requireMemory(oldId)
      } yield result

    private def requireMemory(id: MemoryId): IO[AgentError, MemoryItem] =
      memoryRepo.findById(id).someOrFail(AgentError.NotFound("memory_item", id.value))

    // --- Memory recall ---

    def searchMemory(query: String, personId: Option[PersonId], kind: Option[String], asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryHit]] = {
      val sanitized = sanitizeFtsQuery(query)
      if (sanitized.isEmpty) ZIO.succeed(Nil)
      else
        for {
          raw <- memoryRepo.searchFts(sanitized, personId, kind, Some("accepted"), asOf, math.max(limit * 5, 50))
          now <- Clock.instant
        } yield rerank(raw, now).take(limit)
    }

    def contextBundle(personId: Option[PersonId], factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle] =
      for {
        facts  <- recentFacts(personId, factLimit)
        events <- recentEvents(eventLimit)
      } yield ContextBundle(facts, events)

    private def recentFacts(personId: Option[PersonId], factLimit: Int): IO[AgentError, List[MemoryHit]] =
      for {
        raw <- memoryRepo.listAccepted(personId, None, math.max(factLimit * 3, 30))
        now <- Clock.instant
      } yield rerank(raw, now).take(factLimit)

    private def recentEvents(eventLimit: Int): IO[AgentError, List[AuditEvent]] =
      ZIO
        .foreach(List(EventCategory.Observation, EventCategory.Decision, EventCategory.SessionNote))(cat =>
          auditRepo.list(Some(cat), None, None, eventLimit)
        )
        .map(_.flatten.sortBy(_.createdAt).reverse.take(eventLimit))

    def findConflicts(personId: Option[PersonId], kind: String, text: String): IO[AgentError, List[MemoryItem]] = {
      val sanitized = sanitizeFtsQuery(text)
      if (sanitized.isEmpty) ZIO.succeed(Nil)
      else memoryRepo.searchFts(sanitized, personId, Some(kind), Some("accepted"), None, 20)
    }

    def profileFacts(limit: Int): IO[AgentError, List[MemoryItem]] =
      Clock.instant.flatMap(now => memoryRepo.listBySourcePrefix("onboarding", Some(now), limit))

    // --- Household graph: entities ---

    def proposeEntity(req: ProposeEntityRequest): IO[AgentError, Entity] =
      for {
        now <- Clock.instant
        e    = Entity(
                 id = EntityId(newUuid), kind = req.kind, name = req.name,
                 attributesJson = req.attributesJson, status = MemoryStatus.Accepted,
                 source = req.source, confidence = req.confidence,
                 supersededById = None, createdAt = now, updatedAt = now
               )
        _   <- entityRepo.create(e)
        _   <- audit("entity.propose", "entity", Some(e.id.value), now)
      } yield e

    def rejectEntity(id: EntityId, reason: Option[String]): IO[AgentError, Entity] = transitionEntity(id, MemoryStatus.Rejected, "entity.reject", reason)

    private def transitionEntity(id: EntityId, to: MemoryStatus, action: String, reason: Option[String]): IO[AgentError, Entity] =
      for {
        _       <- requireEntity(id)
        now     <- Clock.instant
        _       <- entityRepo.updateStatus(id, to, now)
        _       <- auditPayload(action, "entity", Some(id.value), now, StatusChangePayload(reason))
        updated <- requireEntity(id)
      } yield updated

    def supersedeEntity(newId: EntityId, oldId: EntityId): IO[AgentError, Entity] =
      for {
        _      <- requireEntity(newId)
        _      <- requireEntity(oldId)
        now    <- Clock.instant
        _      <- entityRepo.setSupersededBy(oldId, newId, now)
        _      <- audit("entity.supersede", "entity", Some(oldId.value), now)
        result <- requireEntity(oldId)
      } yield result

    def listEntities(kind: Option[String], status: Option[String]): IO[AgentError, List[Entity]] =
      entityRepo.findAll(kind, status)

    def resolveEntities(name: String): IO[AgentError, List[Entity]] =
      entityRepo.findByName(name, Some("accepted"))

    private def requireEntity(id: EntityId): IO[AgentError, Entity] =
      entityRepo.findById(id).someOrFail(AgentError.NotFound("entity", id.value))

    // --- Household graph: relationships ---

    def proposeRelationship(req: ProposeRelationshipRequest): IO[AgentError, Relationship] =
      for {
        now <- Clock.instant
        r    = Relationship(
                 id = RelationshipId(newUuid), fromId = req.fromId, fromKind = req.fromKind,
                 relType = req.relType, toId = req.toId, toKind = req.toKind,
                 status = MemoryStatus.Accepted, source = req.source, confidence = req.confidence,
                 note = req.note, supersededById = None,
                 validFrom = req.validFrom, validUntil = req.validUntil,
                 createdAt = now, updatedAt = now
               )
        _   <- relationshipRepo.create(r)
        _   <- audit("relationship.propose", "relationship", Some(r.id.value), now)
      } yield r

    def rejectRelationship(id: RelationshipId, reason: Option[String]): IO[AgentError, Relationship] = transitionRelationship(id, MemoryStatus.Rejected, "relationship.reject", reason)

    private def transitionRelationship(id: RelationshipId, to: MemoryStatus, action: String, reason: Option[String]): IO[AgentError, Relationship] =
      for {
        _       <- requireRelationship(id)
        now     <- Clock.instant
        _       <- relationshipRepo.updateStatus(id, to, now)
        _       <- auditPayload(action, "relationship", Some(id.value), now, StatusChangePayload(reason))
        updated <- requireRelationship(id)
      } yield updated

    def supersedeRelationship(newId: RelationshipId, oldId: RelationshipId): IO[AgentError, Relationship] =
      for {
        _      <- requireRelationship(newId)
        _      <- requireRelationship(oldId)
        now    <- Clock.instant
        _      <- relationshipRepo.setSupersededBy(oldId, newId, now)
        _      <- audit("relationship.supersede", "relationship", Some(oldId.value), now)
        result <- requireRelationship(oldId)
      } yield result

    def listRelationships(fromId: Option[String], toId: Option[String], relType: Option[String], status: Option[String], asOf: Option[Instant]): IO[AgentError, List[Relationship]] =
      relationshipRepo.findAll(fromId, toId, relType, status, asOf)

    val household: IO[AgentError, HouseholdGraph] =
      for {
        now    <- Clock.instant
        ents   <- entityRepo.findAll(None, Some("accepted"))
        rels   <- relationshipRepo.findAll(None, None, None, Some("accepted"), Some(now))
      } yield HouseholdGraph(ents, rels)

    private def requireRelationship(id: RelationshipId): IO[AgentError, Relationship] =
      relationshipRepo.findById(id).someOrFail(AgentError.NotFound("relationship", id.value))

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

    // --- Approvals (HITL gate) ---

    /** Idempotent propose: an open (requested/approved, not-yet-executed) approval
     *  for the same requester + action + payload is reused, so the agent
     *  re-proposing the same action doesn't pile up duplicates. Emits `requested`
     *  on the event stream so subscribed clients can surface it. */
    def requestApproval(req: RequestApprovalRequest): IO[AgentError, Approval] =
      approvalRepo.findApprovable(req.requestedBy, req.actionType, req.payloadJson).flatMap {
        // Idempotent: the same request already exists. Re-publish `requested` so a
        // repeat ask re-surfaces the still-pending approval to subscribed clients
        // (e.g. if the human missed the first prompt).
        case Some(existing) => approvalHub.publish(ApprovalEvent("requested", existing)).as(existing)
        case None =>
          for {
            now     <- Clock.instant
            // Authored by the trusted core ONLY (never the agent's request) so a
            // compromised agent can't forge a menu whose label diverges from its value.
            options <- optionsFor(req.actionType, req.payloadJson)
            a        = Approval(
                         ApprovalId(newUuid), req.requestedBy, req.requiredPersonId,
                         req.actionType, req.payloadJson, ApprovalStatus.Requested, now, None,
                         continuationSkill = req.continuationSkill,
                         continuationParams = req.continuationParams,
                         channel = req.channel,
                         source = req.source,
                         optionsJson = options
                       )
            _   <- approvalRepo.create(a)
            _   <- auditPayloadRaw(
                     actor = req.requestedBy, action = "approval.request",
                     targetType = "approval", targetId = Some(a.id.value),
                     now = now, payloadJson = req.payloadJson
                   )
            _   <- approvalHub.publish(ApprovalEvent("requested", a)).unit
          } yield a
      }

    def listApprovals(status: Option[String]): IO[AgentError, List[Approval]] =
      // "pending" is the natural word for an awaiting approval, but the stored
      // status is `requested`; accept it as an alias so the obvious query works.
      approvalRepo.findAll(status.map {
        case s if s.equalsIgnoreCase("pending") => "requested"
        case s                                  => s
      })

    def getApproval(id: ApprovalId): IO[AgentError, Option[Approval]] =
      approvalRepo.findById(id)

    /** The human's decision. On approve, the action executes server-side
     *  immediately (person-service holds the credentials; the agent never does),
     *  the result is recorded, and `executed` is emitted (carrying the result +
     *  any continuation) so mycroft can run the saga's next step. On reject, only
     *  the status changes. Re-approving an already-executed approval is a no-op
     *  that returns the stored result. */
    def decideApproval(id: ApprovalId, req: DecideApprovalRequest): IO[AgentError, Approval] =
      requireApproval(id).flatMap { a =>
        // Idempotent: already executed — return the recorded result unchanged.
        if (a.executedAt.isDefined) ZIO.succeed(a)
        else
          for {
            _   <- ZIO.when(a.status != ApprovalStatus.Requested)(
                     ZIO.fail(AgentError.BadRequest(
                       s"approval ${id.value} is already ${ApprovalStatus.asString(a.status)}; only a requested approval can be decided")))
            _   <- a.requiredPersonId match {
                     case Some(required) if !req.decidedBy.contains(required) =>
                       ZIO.fail(AgentError.BadRequest(
                         s"approval ${id.value} must be decided by ${required.value}"))
                     case _ => ZIO.unit
                   }
            // The gate: a valid, unexpired, unused one-time code the agent never saw.
            _   <- verifyAndConsumeCode(id, req.code)
            now <- Clock.instant
            updated <-
              if (req.approve)
                for {
                  // Merge the human's chosen option (if any) into the payload — the
                  // only post-request change to what executes, and it comes from the
                  // trusted-core options, not the agent.
                  effective <- resolveChosenOption(a, req.chosenOptionId)
                  _      <- approvalRepo.decide(id, ApprovalStatus.Approved, req.decidedBy, now)
                  result <- performApproved(effective)
                  doneAt <- Clock.instant
                  _      <- approvalRepo.markExecuted(id, result, doneAt)
                  _      <- auditPayload("approval.approved", "approval", Some(id.value), now, StatusChangePayload(req.reason))
                  _      <- auditPayloadRaw("agent", "approval.executed", "approval", Some(id.value), doneAt, result)
                  done   <- requireApproval(id)
                  _      <- approvalHub.publish(ApprovalEvent("executed", done)).unit
                } yield done
              else
                for {
                  _    <- approvalRepo.decide(id, ApprovalStatus.Rejected, req.decidedBy, now)
                  _    <- auditPayload("approval.rejected", "approval", Some(id.value), now, StatusChangePayload(req.reason))
                  done <- requireApproval(id)
                  _    <- approvalHub.publish(ApprovalEvent("rejected", done)).unit
                } yield done
          } yield updated
      }

    def issueDecisionCode(id: ApprovalId): IO[AgentError, String] =
      for {
        a    <- requireApproval(id)
        _    <- ZIO.when(a.status != ApprovalStatus.Requested || a.executedAt.isDefined)(
                  ZIO.fail(AgentError.BadRequest(s"approval ${id.value} is not awaiting a decision")))
        now  <- Clock.instant
        code  = newCode()
        _    <- approvalRepo.putCode(id, hashCode(code), now.plus(decisionCodeTtl))
      } yield code

    /** Verify the one-time code and consume it. Constant-time compare; rejects
     *  expired/used/missing/mismatched codes. The agent never holds a code, so this
     *  is what blocks it from deciding even if it can reach the endpoint. */
    private def verifyAndConsumeCode(id: ApprovalId, code: String): IO[AgentError, Unit] =
      for {
        now    <- Clock.instant
        stored <- approvalRepo.findCode(id)
        _      <- stored match {
                    case Some(c) if c.usedAt.isEmpty && c.expiresAt.isAfter(now) && constantTimeEquals(hashCode(code), c.codeHash) =>
                      approvalRepo.markCodeUsed(id, now)
                    case _ =>
                      ZIO.fail(AgentError.Validation(s"invalid, expired, or already-used decision code for approval ${id.value}"))
                  }
      } yield ()

    /** Dispatch an approved action to its privileged executor. The authoritative
     *  payload is the one stored on the approval (immutable post-request), so no
     *  agent-supplied argument can redefine what was approved. New action types
     *  register here. `approval.ping` is a no-op echo for testing the full loop
     *  without external scopes; `goal.create` records a durable goal (the agent
     *  can only ever propose one — creation is gated through here). */
    private def performApproved(a: Approval): IO[AgentError, String] =
      a.actionType match {
        case "approval.ping" =>
          ZIO.succeed(s"""{"executed":"approval.ping","payload":${a.payloadJson}}""")
        case "goal.create" =>
          ZIO.fromEither(a.payloadJson.fromJson[ProposeGoalRequest])
            .mapError(e => AgentError.BadRequest(s"goal.create payload: $e"))
            .flatMap(proposeGoal)
            .map(_.toJson)
        case "calendar.create_event" =>
          ZIO.fromEither(a.payloadJson.fromJson[CalendarCreateEventRequest])
            .mapError(e => AgentError.BadRequest(s"calendar.create_event payload: $e"))
            .flatMap(createCalendarEvent)
            .map(_.toJson)
        case other =>
          ZIO.fail(AgentError.BadRequest(s"no executor registered for action_type '$other'"))
      }

    /** The trusted-core decision menu for an action, or None. Authored ONLY here —
     *  never from the agent's request — so a compromised agent can't forge a label
     *  that diverges from the value it carries. (calendar.create_event is wired in
     *  `optionsForCalendar`; other actions have no menu.) */
    private def optionsFor(actionType: String, payloadJson: String): IO[AgentError, Option[String]] =
      actionType match {
        case "calendar.create_event" => optionsForCalendar(payloadJson)
        case _                       => ZIO.succeed(None)
      }

    /** Resolve the human's chosen option into the payload: validate it's a member
     *  of the (trusted) menu, then merge its params over the frozen payload. This
     *  is the ONLY way a value enters the payload after the request is frozen. */
    private def resolveChosenOption(a: Approval, chosenOptionId: Option[String]): IO[AgentError, Approval] =
      a.optionsJson match {
        case None => ZIO.succeed(a)
        case Some(raw) =>
          parseOptions(raw) match {
            case Nil  => ZIO.succeed(a)
            case opts =>
              val pick = chosenOptionId.flatMap(cid => opts.find(_._1 == cid))
                .orElse(if (opts.sizeIs == 1) opts.headOption else None)
              pick match {
                case Some((_, params)) =>
                  ZIO.fromEither(mergePayload(a.payloadJson, params))
                    .mapError(AgentError.BadRequest).map(p => a.copy(payloadJson = p))
                case None =>
                  ZIO.fail(AgentError.Validation(
                    s"approval ${a.id.value} needs a chosen option (one of: ${opts.map(_._1).mkString(", ")})"))
              }
          }
      }

    /** Parse the options menu to `(id, params)` pairs (params default to `{}`). */
    private def parseOptions(raw: String): List[(String, Json)] =
      raw.fromJson[Json].toOption match {
        case Some(Json.Arr(items)) =>
          items.toList.flatMap {
            case Json.Obj(fs) =>
              val m = fs.toMap
              m.get("id").collect { case Json.Str(id) => id }
                .map(id => id -> m.getOrElse("params", Json.Obj()))
            case _ => None
          }
        case _ => Nil
      }

    /** Merge an option's `params` object over the payload object (params win). */
    private def mergePayload(payloadJson: String, params: Json): Either[String, String] =
      payloadJson.fromJson[Json].left.map(e => s"payload not JSON: $e").map { base =>
        (base, params) match {
          case (Json.Obj(b), Json.Obj(p)) => Json.Obj(Chunk.fromIterable((b.toMap ++ p.toMap).toList)).toJson
          case _                          => payloadJson
        }
      }

    private def requireApproval(id: ApprovalId): IO[AgentError, Approval] =
      approvalRepo.findById(id).someOrFail(AgentError.NotFound("approval", id.value))

    // --- One-time decision code crypto ---
    private val codeRng = new SecureRandom()
    /** 128-bit random, hex. Machine-echoed (repl) or carried in a push, not typed. */
    private def newCode(): String = {
      val bytes = new Array[Byte](16)
      codeRng.nextBytes(bytes)
      bytes.map(b => f"${b & 0xff}%02x").mkString
    }
    private def hashCode(code: String): String = {
      val md = MessageDigest.getInstance("SHA-256")
      md.digest(code.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
    }
    private def constantTimeEquals(a: String, b: String): Boolean =
      MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"))

    // --- Goals ---

    /** Like commitments, propose-by-source is idempotent. Re-proposing an existing
     *  source refreshes the mutable fields (title/constraints); `outcome` and
     *  `evidence_rule` are immutable and untouched. */
    def proposeGoal(req: ProposeGoalRequest): IO[AgentError, Goal] =
      for {
        now      <- Clock.instant
        existing <- req.source.filter(isDedupSource) match {
                      case Some(src) => goalRepo.findBySource(req.ownerPersonId, src)
                      case None      => ZIO.none
                    }
        result   <- existing match {
                      case Some(g) =>
                        goalRepo.updateContent(g.id, req.title, req.constraintsJson, req.dueAt, now) *>
                          audit("goal.propose.update", "goal", Some(g.id.value), now)
                            .as(g.copy(title = req.title, constraintsJson = req.constraintsJson, dueAt = req.dueAt, updatedAt = now))
                      case None =>
                        val g = Goal(GoalId(newUuid), req.ownerPersonId, req.title, req.outcome,
                                     req.evidenceRule, req.constraintsJson, GoalStatus.Open, None, req.source, now, now, req.dueAt)
                        goalRepo.create(g) *>
                          audit("goal.propose", "goal", Some(g.id.value), now).as(g)
                    }
      } yield result

    def listGoals(owner: Option[PersonId], status: Option[String]): IO[AgentError, List[Goal]] =
      goalRepo.findAll(owner, status)

    def getGoal(id: GoalId): IO[AgentError, Option[GoalWithEvidence]] =
      goalRepo.findById(id).flatMap {
        case None       => ZIO.succeed(None)
        case Some(goal) => goalEvidenceRepo.findByGoal(id).map(ev => Some(GoalWithEvidence(goal, ev)))
      }

    def updateGoalStatus(id: GoalId, req: UpdateGoalStatusRequest): IO[AgentError, Goal] =
      for {
        _        <- requireGoal(id)
        now      <- Clock.instant
        _        <- goalRepo.updateStatus(id, req.status, req.blockedReason, now)
        _        <- auditPayload(
                      action = s"goal.status.${GoalStatus.asString(req.status)}",
                      targetType = "goal", targetId = Some(id.value),
                      now = now,
                      payload = StatusChangePayload(req.blockedReason)
                    )
        updated  <- requireGoal(id)
      } yield updated

    def appendGoalEvidence(id: GoalId, req: AppendGoalEvidenceRequest): IO[AgentError, GoalEvidence] =
      for {
        _    <- requireGoal(id)
        now  <- Clock.instant
        ev    = GoalEvidence(GoalEvidenceId(newUuid), id, req.kind, req.ref, req.note, now)
        _    <- goalEvidenceRepo.create(ev)
        _    <- auditPayload("goal.evidence.append", "goal", Some(id.value), now,
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
                 targetId = req.targetId,
                 text = req.text, payloadJson = req.payloadJson.getOrElse("{}"),
                 createdAt = now,
                 source = req.source
               )
        _   <- auditRepo.create(ev)
      } yield ev

    def listEvents(category: Option[String], since: Option[Instant], until: Option[Instant], limit: Int): IO[AgentError, List[AuditEvent]] =
      auditRepo.list(category, since, until, limit)

    def searchEvents(query: String, category: Option[String], since: Option[Instant], limit: Int): IO[AgentError, List[EventHit]] = {
      val sanitized = sanitizeFtsQuery(query)
      if (sanitized.isEmpty) ZIO.succeed(Nil)
      else
        auditRepo.searchFts(sanitized, category, since, limit).map(eventsToHits(_, limit))
    }

    private def eventsToHits(events: List[AuditEvent], limit: Int): List[EventHit] =
      events.zipWithIndex.map { case (e, i) => EventHit(e, (limit - i).toDouble) }

    // --- Consolidation ---

    def consolidateMemory(since: Option[Instant]): IO[AgentError, List[MemoryItem]] =
      for {
        notes   <- auditRepo.list(Some(EventCategory.SessionNote), since, None, 200)
        obs     <- auditRepo.list(Some(EventCategory.Observation), since, None, 200)
        all      = notes ++ obs
        already <- memoryRepo.findEventsWithMemory(all.map(_.id))
        fresh    = all.filterNot(e => already.contains(e.id))
        now     <- Clock.instant
        items   <- ZIO.foreach(fresh)(e => consolidateOne(e, now))
        _       <- ZIO.when(items.nonEmpty)(
                     auditPayload("memory.consolidate", "memory", None, now,
                                  ConsolidatePayload(items.size))
                   )
      } yield items

    private def consolidateOne(e: AuditEvent, now: Instant): IO[AgentError, MemoryItem] = {
      val (trust, sender) = classifyOrigin(e.source)
      val item = MemoryItem(
        id = MemoryId(newUuid),
        personId = None,
        status = MemoryStatus.Accepted, kind = MemoryKind.Fact,
        text = e.text.getOrElse(e.payloadJson),
        source = s"event:${e.id.value}", confidence = Some(DefaultConfidence),
        createdAt = now, updatedAt = now,
        originEventId = Some(e.id),
        trust = trust, sender = sender
      )
      memoryRepo.create(item).as(item)
    }

    /** Derive a belief's trust + sender from the originating event's provenance.
     *  A claim from untrusted external content (`email:…`, `web:…`, a URL) is
     *  `ExternalContent` — usable for reasoning but never an authoritative profile
     *  fact (the profile is onboarding-sourced) nor sufficient to authorize a gated
     *  action. Everything else (chat/agent/none) is the agent's own inference. The
     *  sender is parsed from an `email:<id>|<sender>` suffix when present. */
    private def classifyOrigin(source: Option[String]): (TrustLevel, Option[String]) =
      source.map(_.toLowerCase) match {
        case Some(s) if s.startsWith("email:") || s.startsWith("web:") || s.startsWith("http") =>
          val sender = source.flatMap(_.split('|').drop(1).headOption.map(_.trim).filter(_.nonEmpty))
          (TrustLevel.ExternalContent, sender)
        case _ =>
          (TrustLevel.AgentInference, None)
      }

    // --- Channels & messages ---

    def createChannel(req: CreateChannelRequest): IO[AgentError, ChannelWithMembers] =
      for {
        now <- Clock.instant
        ch   = Channel(req.id, req.defaultModel, now)
        _   <- channelRepo.create(ch)
        _   <- ZIO.foreachDiscard(req.members)(p => channelMemberRepo.add(ChannelMember(req.id, p)))
        members <- channelMemberRepo.findByChannel(req.id)
      } yield ChannelWithMembers(ch, members.map(_.personId))

    val listChannels: IO[AgentError, List[Channel]] = channelRepo.findAll

    def getChannel(id: ChannelId): IO[AgentError, Option[ChannelWithMembers]] =
      channelRepo.findById(id).flatMap {
        case None     => ZIO.succeed(None)
        case Some(ch) => channelMemberRepo.findByChannel(id).map(ms => Some(ChannelWithMembers(ch, ms.map(_.personId))))
      }

    def addChannelMember(id: ChannelId, personId: PersonId): IO[AgentError, ChannelWithMembers] =
      for {
        ch      <- channelRepo.findById(id).someOrFail(AgentError.NotFound("channel", id.value))
        _       <- channelMemberRepo.add(ChannelMember(id, personId))
        members <- channelMemberRepo.findByChannel(id)
      } yield ChannelWithMembers(ch, members.map(_.personId))

    def appendMessage(req: AppendMessageRequest): IO[AgentError, Message] =
      for {
        now <- Clock.instant
        m    = Message(
                 id = MessageId(newUuid), channelId = req.channelId, role = req.role,
                 personIdFrom = req.personIdFrom, content = req.content,
                 toolCallsJson = req.toolCallsJson, externalId = req.externalId,
                 createdAt = now
               )
        _   <- messageRepo.create(m)
      } yield m

    def listMessages(channelId: ChannelId, since: Option[Instant], limit: Int): IO[AgentError, List[Message]] =
      messageRepo.findByChannel(channelId, since, limit)

    // --- Gmail / inbox ---

    def gmailAuthUrl(ownerPersonId: PersonId): IO[AgentError, GmailAuthUrlResponse] =
      for {
        _        <- requirePerson(ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
      } yield GmailAuthUrlResponse(
        url = GmailOAuth.authUrl(settings, ownerPersonId.value),
        redirectUri = settings.redirectUri
      )

    def gmailOAuthExchange(req: GmailOAuthExchangeRequest): IO[AgentError, GmailCredentialSummary] =
      for {
        _        <- requirePerson(req.ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        token    <- GmailOAuth.exchangeCode(settings, req.code)
        email    <- GmailOAuth.fetchUserEmail(token.accessToken)
        now      <- Clock.instant
        existing <- credentialRepo.findByOwner(GmailConfig.ProviderName, req.ownerPersonId)
        refresh   = token.refreshToken.orElse(existing.map(_.refreshToken))
        refreshTok <- ZIO.fromOption(refresh).orElseFail(AgentError.Validation("No refresh token returned; re-run auth with prompt=consent"))
        cred      = Credential(
                      id = existing.map(_.id).getOrElse(CredentialId(newUuid)),
                      provider = GmailConfig.ProviderName,
                      accountEmail = email.email,
                      ownerPersonId = req.ownerPersonId,
                      accessToken = token.accessToken,
                      refreshToken = refreshTok,
                      expiresAt = GmailOAuth.expiresAtFrom(token, now),
                      scopes = token.scope,
                      updatedAt = now
                    )
        _        <- credentialRepo.upsert(cred)
        _        <- audit("gmail.oauth", "credential", Some(cred.id.value), now)
      } yield GmailCredentialSummary(cred.provider, cred.accountEmail, cred.ownerPersonId, cred.scopes)

    def senderAuthUrl(ownerPersonId: PersonId): IO[AgentError, GmailAuthUrlResponse] =
      for {
        _        <- requirePerson(ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
      } yield GmailAuthUrlResponse(
        url = GmailOAuth.authUrl(settings, GmailConfig.SenderStatePrefix + ownerPersonId.value, GmailConfig.SenderScopes),
        redirectUri = settings.redirectUri
      )

    def senderOAuthExchange(ownerPersonId: PersonId, code: String): IO[AgentError, GmailCredentialSummary] =
      for {
        _         <- requirePerson(ownerPersonId)
        settings  <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        token     <- GmailOAuth.exchangeCode(settings, code)
        email     <- GmailOAuth.fetchAccountEmail(token.accessToken)
        now       <- Clock.instant
        existing  <- credentialRepo.findByOwner(GmailConfig.SenderProvider, ownerPersonId)
        refresh    = token.refreshToken.orElse(existing.map(_.refreshToken))
        refreshTok <- ZIO.fromOption(refresh).orElseFail(AgentError.Validation("No refresh token returned; re-run sender auth with prompt=consent"))
        cred       = Credential(
                       id = existing.map(_.id).getOrElse(CredentialId(newUuid)),
                       provider = GmailConfig.SenderProvider,
                       accountEmail = email.email,
                       ownerPersonId = ownerPersonId,
                       accessToken = token.accessToken,
                       refreshToken = refreshTok,
                       expiresAt = GmailOAuth.expiresAtFrom(token, now),
                       scopes = token.scope,
                       updatedAt = now
                     )
        _         <- credentialRepo.upsert(cred)
        _         <- audit("gmail.sender.oauth", "credential", Some(cred.id.value), now)
      } yield GmailCredentialSummary(cred.provider, cred.accountEmail, cred.ownerPersonId, cred.scopes)

    def gmailSync(ownerPersonId: PersonId, since: Option[Instant]): IO[AgentError, GmailSyncResult] =
      for {
        _        <- requirePerson(ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        cred     <- credentialRepo.findByOwner(GmailConfig.ProviderName, ownerPersonId)
                      .someOrFail(AgentError.NotFound("gmail credential", ownerPersonId.value))
        access   <- ensureAccessToken(settings, cred)
        query     = buildGmailQuery(since)
        ids      <- GmailClient.listMessageIds(access, query, maxResults = 50)
        now      <- Clock.instant
        inserted <- ZIO.foldLeft(ids)(0) { (count, extId) =>
                      syncOneMessage(access, extId, ownerPersonId, now).map(n => count + n)
                    }
        pending  <- inboxRepo.countPending(ownerPersonId)
      } yield GmailSyncResult(fetched = ids.size, inserted = inserted, pending = pending)

    def listInbox(ownerPersonId: PersonId, status: Option[String], limit: Int, oldestFirst: Boolean = false): IO[AgentError, List[InboxSummary]] =
      requirePerson(ownerPersonId) *> inboxRepo.findAll(Some(ownerPersonId), status, limit, oldestFirst).map(_.map(toInboxSummary))

    def getInbox(id: InboxMessageId): IO[AgentError, Option[InboxMessage]] =
      inboxRepo.findById(id)

    /** Fetch an attachment's bytes on demand: resolve the message (for its
     *  provider message id + owner), confirm the attachment is one we know
     *  about, refresh the owner's token, and pull the bytes from Gmail. */
    def downloadAttachment(id: InboxMessageId, attachmentId: String): IO[AgentError, AttachmentDownload] =
      for {
        msg      <- inboxRepo.findById(id).someOrFail(AgentError.NotFound("inbox message", id.value))
        meta     <- ZIO.fromOption(msg.attachments.find(_.attachmentId == attachmentId))
                      .orElseFail(AgentError.NotFound("attachment", attachmentId))
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        cred     <- credentialRepo.findByOwner(GmailConfig.ProviderName, msg.ownerPersonId)
                      .someOrFail(AgentError.NotFound("gmail credential", msg.ownerPersonId.value))
        access   <- ensureAccessToken(settings, cred)
        bytes    <- GmailClient.getAttachment(access, msg.externalId, attachmentId)
      } yield AttachmentDownload(
        filename = meta.filename,
        mimeType = meta.mimeType,
        sizeBytes = bytes.length.toLong,
        dataBase64 = java.util.Base64.getEncoder.encodeToString(bytes)
      )

    def skipInbox(id: InboxMessageId): IO[AgentError, InboxMessage] =
      updateInboxStatus(id, TriageStatus.Skipped, None)

    def markInboxTriaged(id: InboxMessageId, sourceEventId: Option[EventId]): IO[AgentError, InboxMessage] =
      updateInboxStatus(id, TriageStatus.Triaged, sourceEventId)

    // --- Calendar (read-only) ---

    /** Live agenda across ALL the owner's Google calendars (so conflict checks see
     *  every calendar, not just primary). Lists calendars, fans out `listEvents`,
     *  merges and sorts by start. Reuses the single `gmail` Google credential.
     *  On-demand only — no local cache. */
    def calendarAgenda(ownerPersonId: PersonId, timeMin: Instant, timeMax: Instant): IO[AgentError, List[CalendarEvent]] =
      for {
        _        <- requirePerson(ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        cred     <- credentialRepo.findByOwner(GmailConfig.ProviderName, ownerPersonId)
                      .someOrFail(AgentError.NotFound("google credential", ownerPersonId.value))
        access   <- ensureAccessToken(settings, cred)
        cals     <- CalendarClient.listCalendars(access).mapError(scopeHint)
        // Fall back to primary if the calendar list is empty/unavailable.
        ids       = if (cals.isEmpty) List("primary") else cals.map(_._1)
        nested   <- ZIO.foreachPar(ids)(id =>
                      CalendarClient.listEvents(access, id, timeMin, timeMax, maxResults = 100).mapError(scopeHint)
                    )
        events    = nested.flatten.sortBy(_.start)
      } yield events

    /** The owner's Google calendars (read-only). Lets the agent answer "what
     *  calendars do I have?" — it can see them but never selects one for a create
     *  (that's the human's HITL choice). */
    def listCalendars(ownerPersonId: PersonId): IO[AgentError, List[CalendarSummary]] =
      for {
        _        <- requirePerson(ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        cred     <- credentialRepo.findByOwner(GmailConfig.ProviderName, ownerPersonId)
                      .someOrFail(AgentError.NotFound("google credential", ownerPersonId.value))
        access   <- ensureAccessToken(settings, cred)
        cals     <- CalendarClient.listCalendars(access).mapError(scopeHint)
      } yield cals.map { case (id, summary) => CalendarSummary(id, summary) }

    /** One calendar target: where to write, whether to redact to a busy-block, and
     *  the source-suffix that keeps its idempotency key distinct. */
    private case class CalTarget(calendarId: String, redacted: Boolean, sourceSuffix: String)

    def createCalendarEventDirect(req: CalendarCreateEventRequest): IO[AgentError, CalendarEvent] =
      for {
        _        <- requirePerson(req.ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        cred     <- credentialRepo.findByOwner(GmailConfig.ProviderName, req.ownerPersonId)
                      .someOrFail(AgentError.NotFound("google credential", req.ownerPersonId.value))
        access   <- ensureAccessToken(settings, cred)
        vis       = req.visibility.map(_.trim.toLowerCase).getOrElse("private-busy")
        // Family calendar is needed for `family`/`private-busy`. If it can't be
        // resolved we degrade to private-only — never write the wrong place.
        family   <- if (vis == "private") ZIO.none else familyCalendarId(access).catchAll(_ => ZIO.none)
        targets   = computeTargets(vis, family)
        events   <- ZIO.foreach(targets)(t => createOneTarget(req, access, t))
        // The owner-facing event is the first target (full event on the owner's or
        // the family calendar); the busy-block, if any, follows.
        head     <- ZIO.fromOption(events.headOption).orElseFail(AgentError.BadRequest("no calendar target resolved"))
      } yield head

    /** Map visibility → ordered targets (the owner-facing full event first). */
    private def computeTargets(visibility: String, family: Option[String]): List[CalTarget] = {
      val privateFull = CalTarget("primary", redacted = false, sourceSuffix = "")
      visibility match {
        case "family"  => family.map(f => CalTarget(f, redacted = false, "")).toList match {
                            case Nil => List(privateFull) // no family calendar → keep it private (don't drop it)
                            case ts  => ts
                          }
        case "private" => List(privateFull)
        case _ /* private-busy (default) */ =>
          privateFull :: family.map(f => CalTarget(f, redacted = true, "#fam-busy")).toList
      }
    }

    /** Create (or no-op if already present) one target, [M]-marked, mirrored. */
    private def createOneTarget(req: CalendarCreateEventRequest, access: String, t: CalTarget): IO[AgentError, CalendarEvent] = {
      val summary = if (t.redacted) "[M] Busy"
                    else if (req.summary.startsWith("[M] ")) req.summary
                    else s"[M] ${req.summary}"
      val toCreate = if (t.redacted) req.copy(summary = summary, location = None, description = None)
                     else req.copy(summary = summary)
      val srcKey   = req.source.map(_ + t.sourceSuffix)
      for {
        existing <- srcKey match {
                      case Some(s) => calendarEventRepo.findBySource(req.ownerPersonId, s)
                      case None    => calendarEventRepo.findBySignature(req.ownerPersonId, t.calendarId, summary, req.start)
                    }
        event    <- existing match {
                      case Some(e) => ZIO.succeed(e)
                      case None =>
                        for {
                          created <- CalendarClient.createEvent(access, t.calendarId, toCreate).mapError(scopeHint)
                          now     <- Clock.instant
                          _       <- audit("calendar.event.created", "calendar_event", Some(created.externalId), now)
                          _       <- calendarEventRepo.upsert(req.ownerPersonId, created, now, srcKey)
                        } yield created
                    }
      } yield event
    }

    /** The owner's Family (shared) calendar id: `FAMILY_CALENDAR_ID` env, else the
     *  calendar whose summary matches `FAMILY_CALENDAR_NAME` (default "Family"). */
    private def familyCalendarId(access: String): IO[AgentError, Option[String]] =
      sys.env.get("FAMILY_CALENDAR_ID").map(_.trim).filter(_.nonEmpty) match {
        case Some(id) => ZIO.some(id)
        case None =>
          val name = sys.env.get("FAMILY_CALENDAR_NAME").map(_.trim).filter(_.nonEmpty).getOrElse("Family")
          CalendarClient.listCalendars(access).mapError(scopeHint)
            .map(_.collectFirst { case (id, summary) if summary.equalsIgnoreCase(name) => id })
      }

    // --- Google Tasks projection (commitments are SoT; Tasks is the view) ---

    def syncTasks(ownerPersonId: PersonId): IO[AgentError, TaskSyncResult] =
      for {
        _        <- requirePerson(ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        cred     <- credentialRepo.findByOwner(GmailConfig.ProviderName, ownerPersonId)
                      .someOrFail(AgentError.NotFound("google credential", ownerPersonId.value))
        access   <- ensureAccessToken(settings, cred)
        lists    <- TasksClient.listTaskLists(access).mapError(tasksScopeHint)
        listId   <- ZIO.fromOption(lists.headOption.map(_._1))
                      .orElseFail(AgentError.Validation("no Google Tasks list found for this account"))
        pushed   <- pushCommitments(ownerPersonId, access, listId)
        pulled   <- pullTasks(ownerPersonId, access, listId)
      } yield TaskSyncResult(pushed = pushed, pulled = pulled._1, imported = pulled._2)

    /** Push commitments to Tasks: insert new open ones, patch changed open ones,
     *  complete/delete resolved ones. Per-item best-effort — a failure on one
     *  doesn't abort the batch. Returns the count successfully pushed. */
    private def pushCommitments(owner: PersonId, access: String, listId: String): IO[AgentError, Int] =
      commitmentRepo.findForTaskSync(owner).flatMap { syncs =>
        ZIO.foldLeft(syncs)(0) { (n, s) =>
          val c = s.commitment
          // `[M] ` marks the Task as MyCroft-created (visible vs the user's own todos).
          val title = if (c.text.startsWith("[M] ")) c.text else s"[M] ${c.text}"
          val notes = Some(s"[mycroft] source=${c.source}")
          val action: IO[AgentError, Unit] = (s.googleTaskId, c.status) match {
            case (None, CommitmentStatus.Open) =>
              for {
                t   <- TasksClient.insertTask(access, listId, title, notes, c.dueAt)
                now <- Clock.instant
                _   <- commitmentRepo.setTaskMapping(c.id, listId, t.id, now)
              } yield ()
            case (None, _) =>
              ZIO.unit // resolved and never projected — nothing to push
            case (Some(taskId), CommitmentStatus.Open) =>
              val lst = s.googleTaskListId.getOrElse(listId)
              for {
                _   <- TasksClient.patchTask(access, lst, taskId, title = Some(title), due = c.dueAt, status = Some("needsAction"))
                now <- Clock.instant
                _   <- commitmentRepo.markProjected(c.id, now)
              } yield ()
            case (Some(taskId), CommitmentStatus.Done) =>
              completeAndMark(access, s.googleTaskListId.getOrElse(listId), taskId, c.id, complete = true)
            case (Some(taskId), _) => // Ignored / Cancelled / Proposed → remove from the list
              completeAndMark(access, s.googleTaskListId.getOrElse(listId), taskId, c.id, complete = false)
          }
          action.foldZIO(_ => ZIO.succeed(n), _ => ZIO.succeed(n + 1))
        }
      }

    private def completeAndMark(access: String, listId: String, taskId: String, id: CommitmentId, complete: Boolean): IO[AgentError, Unit] =
      (if (complete) TasksClient.completeTask(access, listId, taskId).unit
       else TasksClient.deleteTask(access, listId, taskId)) *>
        Clock.instant.flatMap(commitmentRepo.markProjected(id, _))

    /** Pull Google-side changes back: a mapped task completed/deleted → resolve the
     *  commitment; due-date changed → update it; an unmapped task → import as a new
     *  commitment. Bounded to status + due. Returns (updated, imported). */
    private def pullTasks(owner: PersonId, access: String, listId: String): IO[AgentError, (Int, Int)] =
      TasksClient.listTasks(access, listId, showCompleted = true).flatMap { tasks =>
        ZIO.foldLeft(tasks)((0, 0)) { case ((updated, imported), t) =>
          commitmentRepo.findByGoogleTaskId(t.id).flatMap {
            case Some(c) => reconcileTask(c, t).map(changed => (updated + (if (changed) 1 else 0), imported))
            case None    => importTask(owner, listId, t).map(did => (updated, imported + (if (did) 1 else 0)))
          }.foldZIO(_ => ZIO.succeed((updated, imported)), ZIO.succeed(_))
        }
      }

    /** Apply a Google-side change to a mapped commitment (status, then due). */
    private def reconcileTask(c: Commitment, t: TasksClient.GTask): IO[AgentError, Boolean] =
      Clock.instant.flatMap { now =>
        if ((t.completed || t.deleted) && c.status == CommitmentStatus.Open) {
          val to = if (t.completed) CommitmentStatus.Done else CommitmentStatus.Cancelled
          commitmentRepo.updateStatus(c.id, to, now) *>
            audit(s"commitment.status.${CommitmentStatus.asString(to)}", "commitment", Some(c.id.value), now)
              .as(true)
        } else if (!t.completed && c.status == CommitmentStatus.Open && !sameDay(t.due, c.dueAt)) {
          commitmentRepo.updateContent(c.id, c.text, c.evidence, t.due, now) *>
            audit("commitment.sync.due", "commitment", Some(c.id.value), now).as(true)
        } else ZIO.succeed(false)
      }

    /** Import a user-created (open) task as a new commitment, mapped back so we
     *  don't re-import it. Skips completed/deleted tasks. */
    private def importTask(owner: PersonId, listId: String, t: TasksClient.GTask): IO[AgentError, Boolean] =
      if (t.completed || t.deleted || t.title.trim.isEmpty) ZIO.succeed(false)
      else
        Clock.instant.flatMap { now =>
          val c = Commitment(CommitmentId(newUuid), owner, CommitmentStatus.Open,
                             t.title, s"tasks:${t.id}", "imported from Google Tasks", t.due, now, now)
          commitmentRepo.create(c) *>
            commitmentRepo.setTaskMapping(c.id, listId, t.id, now) *>
            audit("commitment.import.tasks", "commitment", Some(c.id.value), now).as(true)
        }

    /** Compare two optional instants at date granularity (Tasks `due` is date-only). */
    private def sameDay(a: Option[Instant], b: Option[Instant]): Boolean =
      (a, b) match {
        case (Some(x), Some(y)) =>
          x.atZone(java.time.ZoneOffset.UTC).toLocalDate == y.atZone(java.time.ZoneOffset.UTC).toLocalDate
        case (None, None) => true
        case _            => false
      }

    private def tasksScopeHint(e: AgentError): AgentError = e match {
      case AgentError.HttpFailed(m, _) if m.contains("403") || m.toLowerCase.contains("insufficient") =>
        AgentError.Validation("Google Tasks access not granted. The operator must re-run the Google OAuth flow to grant the tasks scope.")
      case other => other
    }

    // --- Calendar mirror (Google Calendar → local event store) ---

    def syncCalendar(ownerPersonId: PersonId): IO[AgentError, CalendarSyncResult] =
      for {
        now    <- Clock.instant
        timeMin = now.minus(java.time.Duration.ofDays(1))
        timeMax = now.plus(java.time.Duration.ofDays(90))
        // Live events across all the owner's calendars (reuses the agenda fan-out).
        live   <- calendarAgenda(ownerPersonId, timeMin, timeMax)
        counts <- ZIO.foldLeft(live)((0, 0)) { case ((imp, upd), e) =>
                    calendarEventRepo.find(ownerPersonId, e.calendarId, e.externalId).flatMap {
                      case None =>
                        calendarEventRepo.upsert(ownerPersonId, e, now) *>
                          audit("calendar.event.imported", "calendar_event", Some(e.externalId), now)
                            .as((imp + 1, upd))
                      case Some(prev) if calendarChanged(prev, e) =>
                        calendarEventRepo.upsert(ownerPersonId, e, now) *>
                          audit("calendar.event.updated", "calendar_event", Some(e.externalId), now)
                            .as((imp, upd + 1))
                      case Some(_) =>
                        // Unchanged — touch synced_at so it isn't seen as vanished.
                        calendarEventRepo.upsert(ownerPersonId, e, now).as((imp, upd))
                    }
                  }
        stale  <- calendarEventRepo.cancelStaleInWindow(ownerPersonId, now, timeMin, timeMax)
        _      <- ZIO.foreachDiscard(stale) { case (_, ext, _) =>
                    audit("calendar.event.cancelled", "calendar_event", Some(ext), now)
                  }
      } yield CalendarSyncResult(imported = counts._1, updated = counts._2, cancelled = stale.size)

    /** A Google-side change worth mirroring: time, status, or title moved. */
    private def calendarChanged(prev: CalendarEvent, next: CalendarEvent): Boolean =
      prev.start != next.start || prev.end != next.end || prev.status != next.status || prev.summary != next.summary

    // --- Daily briefing (agent composes via submit; person-service delivers) ---

    def submitBriefing(req: SubmitBriefingRequest): IO[AgentError, Briefing] =
      for {
        _   <- requirePerson(req.ownerPersonId)
        now <- Clock.instant
        b    = Briefing(newUuid, req.ownerPersonId, req.subject, req.body, "pending", None, now, None, None)
        _   <- briefingRepo.create(b)
        _   <- audit("briefing.submitted", "briefing", Some(b.id), now)
        out <- deliverOne(b)
      } yield out

    def deliverPending(ownerPersonId: PersonId): IO[AgentError, Int] =
      for {
        _       <- requirePerson(ownerPersonId)
        pending <- briefingRepo.findPending(ownerPersonId)
        results <- ZIO.foreach(pending)(deliverOne)
      } yield results.count(_.status == "delivered")

    /** Deliver one briefing on the owner's channel; record delivered/failed. A
     *  delivery failure is recorded (stays retryable), not propagated. */
    private def deliverOne(b: Briefing): IO[AgentError, Briefing] =
      channelFor(b.ownerPersonId)
        .flatMap(ch => ch.deliver(b.subject, b.body).as(ch.name))
        .foldZIO(
          e   => Clock.instant.flatMap(now => briefingRepo.recordDeliveryError(b.id, e.message, now))
                   .as(b.copy(status = "pending", error = Some(e.message))),
          chN => Clock.instant.flatMap(now => briefingRepo.markDelivered(b.id, chN, now))
                   .as(b.copy(status = "delivered", channel = Some(chN), deliveredAt = None))
        )

    /** The owner's delivery channel. For now everyone uses email-to-self (the
     *  credential's own account address — non-redirectable). Multi-user: resolve a
     *  per-person preference (email / whatsapp / signal) here. */
    private def channelFor(ownerPersonId: PersonId): IO[AgentError, DeliveryChannel] =
      ZIO.succeed(new DeliveryChannel {
        val name = "email"
        def deliver(subject: String, body: String): IO[AgentError, Unit] =
          for {
            settings   <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
            ownerCred  <- credentialRepo.findByOwner(GmailConfig.ProviderName, ownerPersonId)
                            .someOrFail(AgentError.NotFound("google credential", ownerPersonId.value))
            // Prefer the dedicated sender account (From = mycroft.agent@…); fall back
            // to the owner sending to themselves until the sender is authorized.
            senderCred <- credentialRepo.findByOwner(GmailConfig.SenderProvider, ownerPersonId)
            from        = senderCred.map(_.accountEmail).getOrElse(ownerCred.accountEmail)
            authCred    = senderCred.getOrElse(ownerCred)
            access     <- ensureAccessToken(settings, authCred)
            // Render the agent's markdown to HTML — Gmail doesn't show markdown.
            _          <- GmailClient.sendMessage(access, from, ownerCred.accountEmail,
                            subject, Markdown.toHtml(body), "text/html; charset=UTF-8")
          } yield ()
      })

    def runDailyBriefing(ownerPersonId: PersonId): IO[AgentError, Unit] =
      for {
        person     <- requirePerson(ownerPersonId)
        now        <- Clock.instant
        // Gate on the owner's local time (DST-correct via java.time) so the poller
        // can just check in periodically and the server decides when 7am has arrived.
        zone        = scala.util.Try(java.time.ZoneId.of(person.timezone)).getOrElse(java.time.ZoneOffset.UTC: java.time.ZoneId)
        local       = now.atZone(zone)
        hour        = sys.env.get("BRIEFING_HOUR").flatMap(_.toIntOption).getOrElse(7)
        dayStart    = local.toLocalDate.atStartOfDay(zone).toInstant   // local midnight
        already    <- briefingRepo.existsSince(ownerPersonId, dayStart)
        // Fire once per local day, only after the configured local hour.
        _          <- ZIO.unless(already || local.getHour < hour) {
                        // Fresh data first, then ask the agent to compose. Both are
                        // best-effort: a sync hiccup shouldn't block composition.
                        syncCalendar(ownerPersonId).ignore *>
                          syncTasks(ownerPersonId).ignore *>
                          triggerCompose(ownerPersonId)
                      }
      } yield ()

    /** Ask mycroft to compose the briefing (it runs the `daily-briefing` skill and
     *  calls `briefing submit`). Fire-and-forget; the agent has no send capability.
     *  MYCROFT_URL is read from the environment; absent → no-op (logged). */
    private def triggerCompose(ownerPersonId: PersonId): IO[AgentError, Unit] =
      sys.env.get("MYCROFT_URL") match {
        case None => ZIO.unit
        case Some(base) =>
          val owner = ownerPersonId.value
          val body =
            s"""{"channel":"$owner","from":"$owner","content":"Compose today's daily briefing for $owner.","skill":"daily-briefing","params":"{\\"owner\\":\\"$owner\\"}"}"""
          ZIO.attemptBlocking {
            val req = java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create(s"$base/inbound"))
              .header("Content-Type", "application/json")
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
              .build()
            java.net.http.HttpClient.newHttpClient()
              .send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            ()
          }.ignore // fire-and-forget; the poller retries the run if needed
      }

    /** Create an event on the owner's primary calendar (the `calendar.create_event`
     *  executor). Same token path as the agenda read; needs the calendar.events
     *  write scope, so a token minted before the scope bump will 403 → scopeHint. */
    private def createCalendarEvent(req: CalendarCreateEventRequest): IO[AgentError, CalendarEvent] =
      for {
        _        <- requirePerson(req.ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        cred     <- credentialRepo.findByOwner(GmailConfig.ProviderName, req.ownerPersonId)
                      .someOrFail(AgentError.NotFound("google credential", req.ownerPersonId.value))
        access   <- ensureAccessToken(settings, cred)
        target    = req.calendarId.getOrElse("primary")
        event    <- CalendarClient.createEvent(access, target, req).mapError(scopeHint)
        now      <- Clock.instant
        _        <- audit("calendar.event.created", "calendar_event", Some(event.externalId), now)
        // Write through to the local mirror so the agent's event store reflects it
        // immediately, without waiting for the next sync.
        _        <- calendarEventRepo.upsert(req.ownerPersonId, event, now).ignore
      } yield event

    /** Build the HITL calendar menu from the owner's real Google calendars.
     *  Best-effort: if the owner has 0–1 writable calendars (or listing fails),
     *  return None and execution defaults to "primary" — no choice is forced.
     *  Labels are the calendars' own summaries — authored here, never by the agent. */
    private def optionsForCalendar(payloadJson: String): IO[AgentError, Option[String]] =
      ZIO.fromEither(payloadJson.fromJson[CalendarCreateEventRequest])
        .mapError(e => AgentError.BadRequest(s"calendar.create_event payload: $e"))
        .flatMap { req =>
          (for {
            settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
            cred     <- credentialRepo.findByOwner(GmailConfig.ProviderName, req.ownerPersonId)
                          .someOrFail(AgentError.NotFound("google credential", req.ownerPersonId.value))
            access   <- ensureAccessToken(settings, cred)
            cals     <- CalendarClient.listCalendars(access).mapError(scopeHint)
          } yield cals match {
            case cals if cals.sizeIs <= 1 => None
            case cals =>
              val menu = cals.map { case (id, summary) =>
                Json.Obj(
                  "id"     -> Json.Str(id),
                  "label"  -> Json.Str(summary),
                  "params" -> Json.Obj("calendarId" -> Json.Str(id))
                )
              }
              Some(Json.Arr(Chunk.fromIterable(menu)).toJson)
          })
          // If anything goes wrong sourcing the menu, fall back to no options
          // (single-calendar behaviour) rather than blocking the request.
          .catchAll(_ => ZIO.succeed(None))
        }

    /** Google returns 403 when the granted token lacks the needed calendar scope
     *  (e.g. the owner authed before calendar write was added). Actionable advice. */
    private def scopeHint(e: AgentError): AgentError = e match {
      case AgentError.HttpFailed(m, _) if m.contains("403") || m.toLowerCase.contains("insufficient") =>
        AgentError.Validation("Calendar access not granted for this Google account. The operator must re-run the Gmail OAuth flow (GET /gmail/auth-url then complete consent) to grant calendar access (calendar.events).")
      case other => other
    }

    private def updateInboxStatus(id: InboxMessageId, status: TriageStatus, sourceEventId: Option[EventId]): IO[AgentError, InboxMessage] =
      for {
        _       <- inboxRepo.findById(id).someOrFail(AgentError.NotFound("inbox message", id.value))
        now     <- Clock.instant
        _       <- inboxRepo.updateStatus(id, status, now, sourceEventId)
        updated <- inboxRepo.findById(id).someOrFail(AgentError.NotFound("inbox message", id.value))
        _       <- audit(s"inbox.${TriageStatus.asString(status)}", "inbox_message", Some(id.value), now)
      } yield updated

    private def ensureAccessToken(settings: GmailConfig.Settings, cred: Credential): IO[AgentError, String] =
      Clock.instant.flatMap { now =>
        if (cred.expiresAt.isAfter(now.plusSeconds(60))) ZIO.succeed(cred.accessToken)
        else
          for {
            token   <- GmailOAuth.refreshAccessToken(settings, cred.refreshToken)
            refreshed = cred.copy(
                          accessToken = token.accessToken,
                          expiresAt = GmailOAuth.expiresAtFrom(token, now),
                          scopes = token.scope,
                          updatedAt = now
                        )
            _       <- credentialRepo.upsert(refreshed)
          } yield refreshed.accessToken
      }

    private def buildGmailQuery(since: Option[Instant]): String = {
      val base = "is:unread OR newer_than:7d"
      since.map(s => s"$base after:${s.getEpochSecond}").getOrElse(base)
    }

    /** Sync one message. New messages are inserted (return 1). A message we
     *  already have but that is still `pending` is re-fetched and re-parsed, so
     *  improvements to `MessageParser` heal previously-ingested bodies (return 0
     *  — not new). An already-triaged message is left untouched and not re-fetched. */
    private def syncOneMessage(accessToken: String, externalId: String, owner: PersonId, now: Instant): IO[AgentError, Int] =
      inboxRepo.findByExternal(GmailConfig.ProviderName, externalId, owner).flatMap {
        case Some(existing) if existing.triageStatus != TriageStatus.Pending =>
          ZIO.succeed(0)
        case existingOpt =>
          for {
            parsed <- GmailClient.getMessage(accessToken, externalId).flatMap {
                        case Some(p) => ZIO.succeed(p)
                        case None    => ZIO.fail(AgentError.DecodeFailed(s"Could not parse Gmail message $externalId"))
                      }
            atts    = parsed.attachments.map(a => InboxAttachment(a.attachmentId, a.filename, a.mimeType, a.sizeBytes))
            body    = truncateBody(parsed.bodyText)
            inserted <- existingOpt match {
                          case Some(existing) =>
                            inboxRepo.updateContent(existing.id, parsed.subject, body, atts).as(0)
                          case None =>
                            inboxRepo.upsert(InboxMessage(
                              id = InboxMessageId(newUuid),
                              provider = GmailConfig.ProviderName,
                              externalId = parsed.id,
                              threadId = parsed.threadId,
                              fromAddr = parsed.from,
                              subject = parsed.subject,
                              bodyText = body,
                              receivedAt = Instant.ofEpochMilli(parsed.internalDateMillis),
                              ownerPersonId = owner,
                              triageStatus = TriageStatus.Pending,
                              triagedAt = None,
                              sourceEventId = None,
                              attachments = atts
                            )).as(1)
                        }
          } yield inserted
      }

    private def truncateBody(text: String, maxLen: Int = 16000): String =
      if (text.length <= maxLen) text else text.take(maxLen) + "\n...[truncated]"

    private def requirePerson(id: PersonId): IO[AgentError, Person] =
      personRepo.findById(id).someOrFail(AgentError.NotFound("person", id.value))

    // --- Audit helpers ---

    /** Audit event with no payload (empty JSON object). */
    private def audit(action: String, targetType: String, targetId: Option[String], now: Instant): IO[AgentError, Unit] =
      writeEvent(actor = "agent", action = action, targetType = targetType, targetId = targetId,
                 now = now, payloadJson = "{}")

    /** Audit event with a structured payload, encoded via the supplied codec. */
    private def auditPayload[P: JsonEncoder](action: String, targetType: String, targetId: Option[String], now: Instant, payload: P): IO[AgentError, Unit] =
      writeEvent(actor = "agent", action = action, targetType = targetType, targetId = targetId,
                 now = now, payloadJson = payload.toJson)

    /** Escape hatch for audit events whose payload arrives as opaque JSON
     *  from the caller (approval payloads, mostly). */
    private def auditPayloadRaw(actor: String, action: String, targetType: String, targetId: Option[String], now: Instant, payloadJson: String): IO[AgentError, Unit] =
      writeEvent(actor = actor, action = action, targetType = targetType, targetId = targetId,
                 now = now, payloadJson = payloadJson)

    private def writeEvent(actor: String, action: String, targetType: String, targetId: Option[String], now: Instant, payloadJson: String): IO[AgentError, Unit] =
      auditRepo.create(AuditEvent(
        id = EventId(newUuid), actor = actor, action = action, category = EventCategory.State,
        targetType = targetType, targetId = targetId,
        text = None, payloadJson = payloadJson, createdAt = now
      ))

    // --- Misc helpers ---

    private def newUuid: String = UUID.randomUUID().toString

    /** A source is a dedup key only when it is a namespaced external reference
     *  (e.g. `email:gmail-msg-X`, `event:<id>`). Generic sources like `chat` or
     *  `manual` carry no identity and must never collapse distinct items. */
    private def isDedupSource(source: String): Boolean = {
      val s = source.trim
      s.contains(":") && !s.endsWith(":")
    }

    private def sanitizeFtsQuery(raw: String): String = {
      val cleaned = raw.map(c => if (c.isLetterOrDigit) c else ' ')
      val tokens  = cleaned.split("\\s+").iterator.filter(_.length >= 2).toList
      if (tokens.isEmpty) "" else tokens.map(_ + "*").mkString(" OR ")
    }
  }
}
