package dev.freskog.agent.person.service

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.persistence._
import dev.freskog.agent.person.gmail._
import dev.freskog.agent.person.calendar._

import zio._
import zio.json._

import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration, Instant}
import java.util.UUID

trait PersonService {
  def createPerson(req: CreatePersonRequest): IO[AgentError, Person]
  def listPersons: IO[AgentError, List[Person]]
  def proposeCommitment(req: ProposeCommitmentRequest): IO[AgentError, Commitment]
  def listCommitments(owner: Option[PersonId], status: Option[String]): IO[AgentError, List[Commitment]]
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
  def gmailSync(ownerPersonId: PersonId, since: Option[Instant]): IO[AgentError, GmailSyncResult]
  def listInbox(ownerPersonId: PersonId, status: Option[String], limit: Int, oldestFirst: Boolean = false): IO[AgentError, List[InboxMessage]]
  def getInbox(id: InboxMessageId): IO[AgentError, Option[InboxMessage]]
  def downloadAttachment(id: InboxMessageId, attachmentId: String): IO[AgentError, AttachmentDownload]
  def skipInbox(id: InboxMessageId): IO[AgentError, InboxMessage]
  def markInboxTriaged(id: InboxMessageId, sourceEventId: Option[EventId]): IO[AgentError, InboxMessage]

  // Calendar (read-only)
  def calendarAgenda(ownerPersonId: PersonId, timeMin: Instant, timeMax: Instant): IO[AgentError, List[CalendarEvent]]
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
    approvalHub: Hub[ApprovalEvent],
    decisionCodeTtl: Duration = Duration.ofHours(48)
  ): PersonService = new PersonService {

    // --- Persons ---

    def createPerson(req: CreatePersonRequest): IO[AgentError, Person] = {
      val p = Person(req.id, req.displayName, req.timezone, req.defaultLocale, active = true)
      personRepo.create(p).as(p)
    }

    val listPersons: IO[AgentError, List[Person]] = personRepo.findAll

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
                        val c = Commitment(CommitmentId(newUuid), req.ownerPersonId,
                                           CommitmentStatus.Proposed, req.text, req.source, req.evidence,
                                           req.dueAt, now, now)
                        commitmentRepo.create(c) *>
                          audit("commitment.propose", "commitment", Some(c.id.value), now).as(c)
                    }
      } yield result

    def listCommitments(owner: Option[PersonId], status: Option[String]): IO[AgentError, List[Commitment]] =
      commitmentRepo.findAll(owner, status)

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
                 originEventId = req.originEventId
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
        case Some(existing) => ZIO.succeed(existing)
        case None =>
          for {
            now <- Clock.instant
            a    = Approval(
                     ApprovalId(newUuid), req.requestedBy, req.requiredPersonId,
                     req.actionType, req.payloadJson, ApprovalStatus.Requested, now, None,
                     continuationSkill = req.continuationSkill,
                     continuationParams = req.continuationParams,
                     channel = req.channel
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
      approvalRepo.findAll(status)

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
                  _      <- approvalRepo.decide(id, ApprovalStatus.Approved, req.decidedBy, now)
                  result <- performApproved(a)
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
        case other =>
          ZIO.fail(AgentError.BadRequest(s"no executor registered for action_type '$other'"))
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
                        goalRepo.updateContent(g.id, req.title, req.constraintsJson, now) *>
                          audit("goal.propose.update", "goal", Some(g.id.value), now)
                            .as(g.copy(title = req.title, constraintsJson = req.constraintsJson, updatedAt = now))
                      case None =>
                        val g = Goal(GoalId(newUuid), req.ownerPersonId, req.title, req.outcome,
                                     req.evidenceRule, req.constraintsJson, GoalStatus.Open, None, req.source, now, now)
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
                 createdAt = now
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
      val item = MemoryItem(
        id = MemoryId(newUuid),
        personId = None,
        status = MemoryStatus.Accepted, kind = MemoryKind.Fact,
        text = e.text.getOrElse(e.payloadJson),
        source = s"event:${e.id.value}", confidence = Some(DefaultConfidence),
        createdAt = now, updatedAt = now,
        originEventId = Some(e.id)
      )
      memoryRepo.create(item).as(item)
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

    def listInbox(ownerPersonId: PersonId, status: Option[String], limit: Int, oldestFirst: Boolean = false): IO[AgentError, List[InboxMessage]] =
      requirePerson(ownerPersonId) *> inboxRepo.findAll(Some(ownerPersonId), status, limit, oldestFirst)

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

    /** Live agenda from the owner's primary Google Calendar. Reuses the single
     *  `gmail` Google credential (the consent now also grants calendar.readonly).
     *  Phase 1 is on-demand only — no local cache. */
    def calendarAgenda(ownerPersonId: PersonId, timeMin: Instant, timeMax: Instant): IO[AgentError, List[CalendarEvent]] =
      for {
        _        <- requirePerson(ownerPersonId)
        settings <- ZIO.fromEither(GmailConfig.load).mapError(AgentError.Validation)
        cred     <- credentialRepo.findByOwner(GmailConfig.ProviderName, ownerPersonId)
                      .someOrFail(AgentError.NotFound("google credential", ownerPersonId.value))
        access   <- ensureAccessToken(settings, cred)
        events   <- CalendarClient.listEvents(access, "primary", timeMin, timeMax, maxResults = 100)
                      .mapError(scopeHint)
      } yield events

    /** Google returns 403 when the granted token lacks calendar.readonly (i.e. the
     *  owner authed before calendar was added). Turn that into actionable advice. */
    private def scopeHint(e: AgentError): AgentError = e match {
      case AgentError.HttpFailed(m, _) if m.contains("403") || m.toLowerCase.contains("insufficient") =>
        AgentError.Validation("Calendar access not granted for this Google account. Re-run `person gmail auth --owner <owner>` to grant calendar.readonly.")
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

    private def syncOneMessage(accessToken: String, externalId: String, owner: PersonId, now: Instant): IO[AgentError, Int] =
      inboxRepo.existsExternal(GmailConfig.ProviderName, externalId, owner).flatMap {
        case true => ZIO.succeed(0)
        case false =>
          for {
            parsed <- GmailClient.getMessage(accessToken, externalId).flatMap {
                        case Some(p) => ZIO.succeed(p)
                        case None    => ZIO.fail(AgentError.DecodeFailed(s"Could not parse Gmail message $externalId"))
                      }
            msg     = InboxMessage(
                        id = InboxMessageId(newUuid),
                        provider = GmailConfig.ProviderName,
                        externalId = parsed.id,
                        threadId = parsed.threadId,
                        fromAddr = parsed.from,
                        subject = parsed.subject,
                        bodyText = truncateBody(parsed.bodyText),
                        receivedAt = Instant.ofEpochMilli(parsed.internalDateMillis),
                        ownerPersonId = owner,
                        triageStatus = TriageStatus.Pending,
                        triagedAt = None,
                        sourceEventId = None,
                        attachments = parsed.attachments.map(a =>
                          InboxAttachment(a.attachmentId, a.filename, a.mimeType, a.sizeBytes)
                        )
                      )
            _      <- inboxRepo.upsert(msg)
          } yield 1
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
