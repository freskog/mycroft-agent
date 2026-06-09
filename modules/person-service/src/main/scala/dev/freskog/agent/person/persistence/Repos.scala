package dev.freskog.agent.person.persistence

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.json._

import java.sql.ResultSet
import java.time.Instant

trait PersonRepo {
  def create(person: Person): IO[AgentError, Unit]
  def findAll: IO[AgentError, List[Person]]
  def findById(id: PersonId): IO[AgentError, Option[Person]]
}

trait CommitmentRepo {
  def create(commitment: Commitment): IO[AgentError, Unit]
  def findAll(ownerPersonId: Option[PersonId], status: Option[String]): IO[AgentError, List[Commitment]]
  def findBySource(ownerPersonId: PersonId, source: String): IO[AgentError, Option[Commitment]]
  def updateContent(id: CommitmentId, text: String, evidence: String, dueAt: Option[Instant], updatedAt: Instant): IO[AgentError, Unit]
}

trait MemoryRepo {
  def create(item: MemoryItem): IO[AgentError, Unit]
  def findAll(personId: Option[PersonId], status: Option[String], kind: Option[String]): IO[AgentError, List[MemoryItem]]
  def findById(id: MemoryId): IO[AgentError, Option[MemoryItem]]
  def updateStatus(id: MemoryId, status: MemoryStatus, updatedAt: Instant): IO[AgentError, Unit]
  def setSupersededBy(oldId: MemoryId, newId: MemoryId, updatedAt: Instant): IO[AgentError, Unit]
  def searchFts(query: String, personId: Option[PersonId], kind: Option[String], statusEq: Option[String], asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryItem]]
  def listAccepted(personId: Option[PersonId], asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryItem]]
  /** Accepted, currently-valid, non-superseded facts whose source begins with
   *  `prefix` (e.g. `onboarding`) — the pinned profile, ordered oldest-first. */
  def listBySourcePrefix(prefix: String, asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryItem]]
  def findEventsWithMemory(eventIds: List[EventId]): IO[AgentError, Set[EventId]]
}

trait ApprovalRepo {
  def create(approval: Approval): IO[AgentError, Unit]
  def findAll(status: Option[String]): IO[AgentError, List[Approval]]
  def findById(id: ApprovalId): IO[AgentError, Option[Approval]]
  /** An open (requested or approved, not-yet-redeemed) approval matching the
   *  same requester + action + payload, used to dedupe repeated requests. */
  def findApprovable(requestedBy: String, actionType: String, payloadJson: String): IO[AgentError, Option[Approval]]
  def decide(id: ApprovalId, status: ApprovalStatus, decidedBy: Option[PersonId], decidedAt: Instant): IO[AgentError, Unit]
  def markExecuted(id: ApprovalId, resultJson: String, executedAt: Instant): IO[AgentError, Unit]
  // One-time decision codes (separate table, never joined into Approval, so the
  // hash can't leak onto an agent-readable surface).
  def putCode(approvalId: ApprovalId, codeHash: String, expiresAt: Instant): IO[AgentError, Unit]
  def findCode(approvalId: ApprovalId): IO[AgentError, Option[ApprovalCode]]
  def markCodeUsed(approvalId: ApprovalId, usedAt: Instant): IO[AgentError, Unit]
}

/** Internal-only row: a one-time decision code's hash + lifecycle. Never serialised
 *  to any HTTP surface. */
final case class ApprovalCode(codeHash: String, expiresAt: Instant, usedAt: Option[Instant])

trait AuditRepo {
  def create(event: AuditEvent): IO[AgentError, Unit]
  def list(category: Option[String], since: Option[Instant], until: Option[Instant], limit: Int): IO[AgentError, List[AuditEvent]]
  def searchFts(query: String, category: Option[String], since: Option[Instant], limit: Int): IO[AgentError, List[AuditEvent]]
}

trait GoalRepo {
  def create(goal: Goal): IO[AgentError, Unit]
  def findAll(ownerPersonId: Option[PersonId], status: Option[String]): IO[AgentError, List[Goal]]
  def findById(id: GoalId): IO[AgentError, Option[Goal]]
  def findBySource(ownerPersonId: PersonId, source: String): IO[AgentError, Option[Goal]]
  def updateStatus(id: GoalId, status: GoalStatus, blockedReason: Option[String], updatedAt: Instant): IO[AgentError, Unit]
  def updateContent(id: GoalId, title: String, constraintsJson: Option[String], updatedAt: Instant): IO[AgentError, Unit]
}

trait EntityRepo {
  def create(entity: Entity): IO[AgentError, Unit]
  def findById(id: EntityId): IO[AgentError, Option[Entity]]
  def findAll(kind: Option[String], status: Option[String]): IO[AgentError, List[Entity]]
  def findByName(name: String, status: Option[String]): IO[AgentError, List[Entity]]
  def updateStatus(id: EntityId, status: MemoryStatus, updatedAt: Instant): IO[AgentError, Unit]
  def setSupersededBy(oldId: EntityId, newId: EntityId, updatedAt: Instant): IO[AgentError, Unit]
}

trait RelationshipRepo {
  def create(rel: Relationship): IO[AgentError, Unit]
  def findById(id: RelationshipId): IO[AgentError, Option[Relationship]]
  def findAll(fromId: Option[String], toId: Option[String], relType: Option[String], status: Option[String], asOf: Option[Instant]): IO[AgentError, List[Relationship]]
  def updateStatus(id: RelationshipId, status: MemoryStatus, updatedAt: Instant): IO[AgentError, Unit]
  def setSupersededBy(oldId: RelationshipId, newId: RelationshipId, updatedAt: Instant): IO[AgentError, Unit]
}

trait GoalEvidenceRepo {
  def create(evidence: GoalEvidence): IO[AgentError, Unit]
  def findByGoal(goalId: GoalId): IO[AgentError, List[GoalEvidence]]
}

trait ChannelRepo {
  def create(channel: Channel): IO[AgentError, Unit]
  def findAll: IO[AgentError, List[Channel]]
  def findById(id: ChannelId): IO[AgentError, Option[Channel]]
}

trait ChannelMemberRepo {
  def add(member: ChannelMember): IO[AgentError, Unit]
  def findByChannel(channelId: ChannelId): IO[AgentError, List[ChannelMember]]
}

trait MessageRepo {
  def create(message: Message): IO[AgentError, Unit]
  def findByChannel(channelId: ChannelId, since: Option[Instant], limit: Int): IO[AgentError, List[Message]]
}

trait CredentialRepo {
  def upsert(credential: Credential): IO[AgentError, Unit]
  def findByOwner(provider: String, ownerPersonId: PersonId): IO[AgentError, Option[Credential]]
}

trait InboxMessageRepo {
  def upsert(message: InboxMessage): IO[AgentError, Unit]
  def findById(id: InboxMessageId): IO[AgentError, Option[InboxMessage]]
  def findAll(ownerPersonId: Option[PersonId], status: Option[String], limit: Int, oldestFirst: Boolean = false): IO[AgentError, List[InboxMessage]]
  def existsExternal(provider: String, externalId: String, ownerPersonId: PersonId): IO[AgentError, Boolean]
  def updateStatus(id: InboxMessageId, status: TriageStatus, triagedAt: Instant, sourceEventId: Option[EventId]): IO[AgentError, Unit]
  def countPending(ownerPersonId: PersonId): IO[AgentError, Int]
}

object Repos {

  // --- Row extractors (safe, newtype-aware) ---

  private def col(rs: ResultSet, name: String): String = rs.getString(name)
  private def opt(rs: ResultSet, name: String): Option[String] = Option(rs.getString(name))
  private def optDouble(rs: ResultSet, name: String): Option[Double] = {
    val d = rs.getDouble(name)
    if (rs.wasNull()) None else Some(d)
  }
  private def instant(rs: ResultSet, name: String): Instant         = Instant.parse(col(rs, name))
  private def optInstant(rs: ResultSet, name: String): Option[Instant] = opt(rs, name).map(Instant.parse)

  private def extractPerson(rs: ResultSet): Person =
    Person(
      id = PersonId(col(rs, "id")),
      displayName = col(rs, "display_name"),
      timezone = col(rs, "timezone"),
      defaultLocale = opt(rs, "default_locale"),
      active = rs.getInt("active") != 0
    )

  private def extractCommitment(rs: ResultSet): Commitment =
    Commitment(
      id = CommitmentId(col(rs, "id")),
      ownerPersonId = PersonId(col(rs, "owner_person_id")),
      status = CommitmentStatus.fromString(col(rs, "status")).getOrElse(CommitmentStatus.Proposed),
      text = col(rs, "text"),
      source = col(rs, "source"),
      evidence = col(rs, "evidence"),
      dueAt = optInstant(rs, "due_at"),
      createdAt = instant(rs, "created_at"),
      updatedAt = instant(rs, "updated_at")
    )

  private def extractMemory(rs: ResultSet): MemoryItem =
    MemoryItem(
      id = MemoryId(col(rs, "id")),
      personId = opt(rs, "person_id").map(PersonId),
      status = MemoryStatus.fromString(col(rs, "status")).getOrElse(MemoryStatus.Proposed),
      kind = MemoryKind.fromString(col(rs, "kind")).getOrElse(MemoryKind.Fact),
      text = col(rs, "text"),
      source = col(rs, "source"),
      confidence = optDouble(rs, "confidence"),
      createdAt = instant(rs, "created_at"),
      updatedAt = instant(rs, "updated_at"),
      supersededById = opt(rs, "superseded_by_id").map(MemoryId),
      validFrom = optInstant(rs, "valid_from"),
      validUntil = optInstant(rs, "valid_until"),
      originEventId = opt(rs, "origin_event_id").map(EventId)
    )

  private def extractApproval(rs: ResultSet): Approval =
    Approval(
      id = ApprovalId(col(rs, "id")),
      requestedBy = col(rs, "requested_by"),
      requiredPersonId = opt(rs, "required_person_id").map(PersonId),
      actionType = col(rs, "action_type"),
      payloadJson = col(rs, "payload_json"),
      status = ApprovalStatus.fromString(col(rs, "status")).getOrElse(ApprovalStatus.Requested),
      createdAt = instant(rs, "created_at"),
      decidedAt = optInstant(rs, "decided_at"),
      decidedBy = opt(rs, "decided_by").map(PersonId),
      executedAt = optInstant(rs, "executed_at"),
      resultJson = opt(rs, "result_json"),
      continuationSkill = opt(rs, "continuation_skill"),
      continuationParams = opt(rs, "continuation_params"),
      channel = opt(rs, "channel")
    )

  private def extractEvent(rs: ResultSet): AuditEvent =
    AuditEvent(
      id = EventId(col(rs, "id")),
      actor = col(rs, "actor"),
      action = col(rs, "action"),
      category = opt(rs, "category").getOrElse(EventCategory.State),
      targetType = col(rs, "target_type"),
      targetId = opt(rs, "target_id"),
      text = opt(rs, "text"),
      payloadJson = col(rs, "payload_json"),
      createdAt = instant(rs, "created_at")
    )

  private def extractGoal(rs: ResultSet): Goal =
    Goal(
      id = GoalId(col(rs, "id")),
      ownerPersonId = PersonId(col(rs, "owner_person_id")),
      title = col(rs, "title"),
      outcome = col(rs, "outcome"),
      evidenceRule = col(rs, "evidence_rule"),
      constraintsJson = opt(rs, "constraints_json"),
      status = GoalStatus.fromString(col(rs, "status")).getOrElse(GoalStatus.Open),
      blockedReason = opt(rs, "blocked_reason"),
      source = opt(rs, "source"),
      createdAt = instant(rs, "created_at"),
      updatedAt = instant(rs, "updated_at")
    )

  private def extractGoalEvidence(rs: ResultSet): GoalEvidence =
    GoalEvidence(
      id = GoalEvidenceId(col(rs, "id")),
      goalId = GoalId(col(rs, "goal_id")),
      kind = col(rs, "kind"),
      ref = col(rs, "ref"),
      note = opt(rs, "note"),
      recordedAt = instant(rs, "recorded_at")
    )

  private def extractEntity(rs: ResultSet): Entity =
    Entity(
      id = EntityId(col(rs, "id")),
      kind = EntityKind.fromString(col(rs, "kind")).getOrElse(EntityKind.Other),
      name = col(rs, "name"),
      attributesJson = opt(rs, "attributes_json"),
      status = MemoryStatus.fromString(col(rs, "status")).getOrElse(MemoryStatus.Proposed),
      source = col(rs, "source"),
      confidence = optDouble(rs, "confidence"),
      supersededById = opt(rs, "superseded_by_id").map(EntityId),
      createdAt = instant(rs, "created_at"),
      updatedAt = instant(rs, "updated_at")
    )

  private def extractRelationship(rs: ResultSet): Relationship =
    Relationship(
      id = RelationshipId(col(rs, "id")),
      fromId = col(rs, "from_id"),
      fromKind = NodeKind.fromString(col(rs, "from_kind")).getOrElse(NodeKind.Person),
      relType = col(rs, "rel_type"),
      toId = col(rs, "to_id"),
      toKind = NodeKind.fromString(col(rs, "to_kind")).getOrElse(NodeKind.Person),
      status = MemoryStatus.fromString(col(rs, "status")).getOrElse(MemoryStatus.Proposed),
      source = col(rs, "source"),
      confidence = optDouble(rs, "confidence"),
      note = opt(rs, "note"),
      supersededById = opt(rs, "superseded_by_id").map(RelationshipId),
      validFrom = optInstant(rs, "valid_from"),
      validUntil = optInstant(rs, "valid_until"),
      createdAt = instant(rs, "created_at"),
      updatedAt = instant(rs, "updated_at")
    )

  private def extractChannel(rs: ResultSet): Channel =
    Channel(
      id = ChannelId(col(rs, "id")),
      defaultModel = opt(rs, "default_model"),
      createdAt = instant(rs, "created_at")
    )

  private def extractChannelMember(rs: ResultSet): ChannelMember =
    ChannelMember(
      channelId = ChannelId(col(rs, "channel_id")),
      personId = PersonId(col(rs, "person_id"))
    )

  private def extractMessage(rs: ResultSet): Message =
    Message(
      id = MessageId(col(rs, "id")),
      channelId = ChannelId(col(rs, "channel_id")),
      role = MessageRole.fromString(col(rs, "role")).getOrElse(MessageRole.User),
      personIdFrom = opt(rs, "person_id_from").map(PersonId),
      content = col(rs, "content"),
      toolCallsJson = opt(rs, "tool_calls_json"),
      externalId = opt(rs, "external_id"),
      createdAt = instant(rs, "created_at")
    )

  private def extractCredential(rs: ResultSet): Credential =
    Credential(
      id = CredentialId(col(rs, "id")),
      provider = col(rs, "provider"),
      accountEmail = col(rs, "account_email"),
      ownerPersonId = PersonId(col(rs, "owner_person_id")),
      accessToken = col(rs, "access_token"),
      refreshToken = col(rs, "refresh_token"),
      expiresAt = instant(rs, "expires_at"),
      scopes = col(rs, "scopes"),
      updatedAt = instant(rs, "updated_at")
    )

  private def extractInboxMessage(rs: ResultSet): InboxMessage =
    InboxMessage(
      id = InboxMessageId(col(rs, "id")),
      provider = col(rs, "provider"),
      externalId = col(rs, "external_id"),
      threadId = opt(rs, "thread_id"),
      fromAddr = col(rs, "from_addr"),
      subject = col(rs, "subject"),
      bodyText = col(rs, "body_text"),
      receivedAt = instant(rs, "received_at"),
      ownerPersonId = PersonId(col(rs, "owner_person_id")),
      triageStatus = TriageStatus.fromString(col(rs, "triage_status")).getOrElse(TriageStatus.Pending),
      triagedAt = optInstant(rs, "triaged_at"),
      sourceEventId = opt(rs, "source_event_id").map(EventId),
      attachments = decodeAttachments(opt(rs, "attachments_json"))
    )

  private def decodeAttachments(json: Option[String]): List[InboxAttachment] =
    json.flatMap(_.fromJson[List[InboxAttachment]].toOption).getOrElse(Nil)

  // --- Composable WHERE-clause builder ---

  /** A SQL filter fragment plus the ordered parameters its `?`s consume.
   *  A clause with no binds is allowed (e.g. literal-only `status = 'accepted'`). */
  private final case class Clause(sql: String, params: List[Any] = Nil)
  private object Clause {
    def of[A](sql: String)(value: Option[A]): Option[Clause] =
      value.map(v => Clause(sql, List(v)))
  }

  private def whereSql(prefix: String, clauses: List[Clause]): String =
    if (clauses.isEmpty) "" else clauses.iterator.map(_.sql).mkString(s" $prefix ", " AND ", "")

  private def paramsOf(clauses: List[Clause]): List[Any] =
    clauses.flatMap(_.params)

  // --- Repo implementations ---

  def sqlitePersonRepo(db: Sqlite): PersonRepo = new PersonRepo {
    def create(p: Person): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO persons (id, display_name, timezone, default_locale, active) VALUES (?, ?, ?, ?, ?)",
        p.id, p.displayName, p.timezone, p.defaultLocale, if (p.active) 1 else 0
      )

    def findAll: IO[AgentError, List[Person]] =
      db.query("SELECT * FROM persons")(extractPerson)

    def findById(id: PersonId): IO[AgentError, Option[Person]] =
      db.queryOne("SELECT * FROM persons WHERE id = ?", id)(extractPerson)
  }

  def sqliteCommitmentRepo(db: Sqlite): CommitmentRepo = new CommitmentRepo {
    private def statusStr(s: CommitmentStatus): String = s match {
      case CommitmentStatus.Proposed  => "proposed"
      case CommitmentStatus.Open      => "open"
      case CommitmentStatus.Done      => "done"
      case CommitmentStatus.Ignored   => "ignored"
      case CommitmentStatus.Cancelled => "cancelled"
    }

    def create(c: Commitment): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO commitments (id, owner_person_id, status, text, source, evidence, due_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        c.id, c.ownerPersonId, statusStr(c.status), c.text, c.source, c.evidence,
        c.dueAt, c.createdAt, c.updatedAt
      )

    def findAll(ownerPersonId: Option[PersonId], status: Option[String]): IO[AgentError, List[Commitment]] = {
      val clauses = List(
        Clause.of("owner_person_id = ?")(ownerPersonId),
        Clause.of("status = ?")(status)
      ).flatten
      val sql = s"SELECT * FROM commitments${whereSql("WHERE", clauses)} ORDER BY created_at DESC"
      db.query(sql, paramsOf(clauses): _*)(extractCommitment)
    }

    def findBySource(ownerPersonId: PersonId, source: String): IO[AgentError, Option[Commitment]] =
      db.queryOne(
        "SELECT * FROM commitments WHERE owner_person_id = ? AND source = ? ORDER BY created_at DESC LIMIT 1",
        ownerPersonId, source
      )(extractCommitment)

    def updateContent(id: CommitmentId, text: String, evidence: String, dueAt: Option[Instant], updatedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE commitments SET text = ?, evidence = ?, due_at = ?, updated_at = ? WHERE id = ?",
        text, evidence, dueAt, updatedAt, id
      )
  }

  def sqliteMemoryRepo(db: Sqlite): MemoryRepo = new MemoryRepo {
    def create(m: MemoryItem): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO memory_items (id, person_id, status, kind, text, source, confidence, created_at, updated_at, superseded_by_id, valid_from, valid_until, origin_event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        m.id, m.personId, MemoryStatus.asString(m.status), MemoryKind.asString(m.kind),
        m.text, m.source, m.confidence, m.createdAt, m.updatedAt,
        m.supersededById, m.validFrom, m.validUntil, m.originEventId
      )

    def findAll(personId: Option[PersonId], status: Option[String], kind: Option[String]): IO[AgentError, List[MemoryItem]] = {
      val clauses = List(
        Clause.of("person_id = ?")(personId),
        Clause.of("status = ?")(status),
        Clause.of("kind = ?")(kind)
      ).flatten
      val sql = s"SELECT * FROM memory_items${whereSql("WHERE", clauses)} ORDER BY created_at DESC"
      db.query(sql, paramsOf(clauses): _*)(extractMemory)
    }

    def findById(id: MemoryId): IO[AgentError, Option[MemoryItem]] =
      db.queryOne("SELECT * FROM memory_items WHERE id = ?", id)(extractMemory)

    def updateStatus(id: MemoryId, status: MemoryStatus, updatedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE memory_items SET status = ?, updated_at = ? WHERE id = ?",
        MemoryStatus.asString(status), updatedAt, id
      )

    def setSupersededBy(oldId: MemoryId, newId: MemoryId, updatedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE memory_items SET superseded_by_id = ?, updated_at = ? WHERE id = ?",
        newId, updatedAt, oldId
      )

    def searchFts(query: String, personId: Option[PersonId], kind: Option[String], statusEq: Option[String], asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryItem]] = {
      val clauses = List(
        Some(Clause("memory_items_fts MATCH ?", List(query))),
        Clause.of("m.person_id = ?")(personId),
        Clause.of("m.kind = ?")(kind),
        Clause.of("m.status = ?")(statusEq)
      ).flatten ::: temporalClauses("m", asOf)
      val joinSupersede = if (asOf.isDefined) " LEFT JOIN memory_items ms ON m.superseded_by_id = ms.id" else ""
      val sql =
        s"""SELECT m.* FROM memory_items_fts f
           | JOIN memory_items m ON m.rowid = f.rowid$joinSupersede
           | WHERE ${clauses.map(_.sql).mkString(" AND ")}
           | ORDER BY bm25(memory_items_fts) LIMIT ?""".stripMargin
      db.query(sql, (paramsOf(clauses) :+ limit): _*)(extractMemory)
    }

    def listAccepted(personId: Option[PersonId], asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryItem]] = {
      val clauses = Clause("m1.status = 'accepted'") :: List(
        Clause.of("m1.person_id = ?")(personId)
      ).flatten ::: temporalClauses("m1", asOf)
      val sql =
        s"""SELECT m1.* FROM memory_items m1
           | LEFT JOIN memory_items m2 ON m1.superseded_by_id = m2.id
           | WHERE ${clauses.map(_.sql).mkString(" AND ")}
           | ORDER BY m1.created_at DESC LIMIT ?""".stripMargin
      db.query(sql, (paramsOf(clauses) :+ limit): _*)(extractMemory)
    }

    def listBySourcePrefix(prefix: String, asOf: Option[Instant], limit: Int): IO[AgentError, List[MemoryItem]] = {
      val clauses = List(
        Clause("m1.status = 'accepted'"),
        Clause("m1.source LIKE ?", List(prefix + "%"))
      ) ::: temporalClauses("m1", asOf)
      val sql =
        s"""SELECT m1.* FROM memory_items m1
           | LEFT JOIN memory_items m2 ON m1.superseded_by_id = m2.id
           | WHERE ${clauses.map(_.sql).mkString(" AND ")}
           | ORDER BY m1.created_at ASC LIMIT ?""".stripMargin
      db.query(sql, (paramsOf(clauses) :+ limit): _*)(extractMemory)
    }

    def findEventsWithMemory(eventIds: List[EventId]): IO[AgentError, Set[EventId]] =
      if (eventIds.isEmpty) ZIO.succeed(Set.empty)
      else {
        val placeholders = List.fill(eventIds.size)("?").mkString(",")
        db.query(
          s"SELECT DISTINCT origin_event_id FROM memory_items WHERE origin_event_id IN ($placeholders)",
          eventIds: _*
        )(rs => EventId(col(rs, "origin_event_id"))).map(_.toSet)
      }

    /** Same supersession + valid_from/until filtering used by FTS search and
     *  accepted-list. The supersession-join alias (`ms` vs `m2`) is selected
     *  from the table alias to match the joined LEFT JOIN above each query. */
    private def temporalClauses(alias: String, asOf: Option[Instant]): List[Clause] = asOf match {
      case None =>
        List(Clause(s"$alias.superseded_by_id IS NULL"))
      case Some(ts) =>
        val supersedeAlias = if (alias == "m") "ms" else "m2"
        List(
          Clause(s"$alias.created_at <= ?", List(ts)),
          Clause(s"($alias.superseded_by_id IS NULL OR $supersedeAlias.created_at > ?)", List(ts)),
          Clause(s"($alias.valid_from IS NULL OR $alias.valid_from <= ?)", List(ts)),
          Clause(s"($alias.valid_until IS NULL OR $alias.valid_until > ?)", List(ts))
        )
    }
  }

  def sqliteApprovalRepo(db: Sqlite): ApprovalRepo = new ApprovalRepo {
    private def statusStr(s: ApprovalStatus): String = s match {
      case ApprovalStatus.Requested => "requested"
      case ApprovalStatus.Approved  => "approved"
      case ApprovalStatus.Rejected  => "rejected"
      case ApprovalStatus.Expired   => "expired"
    }

    def create(a: Approval): IO[AgentError, Unit] =
      db.execute(
        """INSERT INTO approvals
          | (id, requested_by, required_person_id, action_type, payload_json, status,
          |  created_at, decided_at, continuation_skill, continuation_params, channel)
          | VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin,
        a.id, a.requestedBy, a.requiredPersonId,
        a.actionType, a.payloadJson, statusStr(a.status), a.createdAt, a.decidedAt,
        a.continuationSkill, a.continuationParams, a.channel
      )

    def findAll(status: Option[String]): IO[AgentError, List[Approval]] = {
      val clauses = List(
        Clause.of("status = ?")(status)
      ).flatten
      val sql = s"SELECT * FROM approvals${whereSql("WHERE", clauses)} ORDER BY created_at DESC"
      db.query(sql, paramsOf(clauses): _*)(extractApproval)
    }

    def findById(id: ApprovalId): IO[AgentError, Option[Approval]] =
      db.queryOne("SELECT * FROM approvals WHERE id = ?", id)(extractApproval)

    def findApprovable(requestedBy: String, actionType: String, payloadJson: String): IO[AgentError, Option[Approval]] =
      db.queryOne(
        """SELECT * FROM approvals
          | WHERE requested_by = ? AND action_type = ? AND payload_json = ?
          |   AND status IN ('requested','approved') AND executed_at IS NULL
          | ORDER BY created_at DESC LIMIT 1""".stripMargin,
        requestedBy, actionType, payloadJson
      )(extractApproval)

    def decide(id: ApprovalId, status: ApprovalStatus, decidedBy: Option[PersonId], decidedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE approvals SET status = ?, decided_by = ?, decided_at = ? WHERE id = ?",
        statusStr(status), decidedBy, decidedAt, id
      )

    def markExecuted(id: ApprovalId, resultJson: String, executedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE approvals SET executed_at = ?, result_json = ? WHERE id = ?",
        executedAt, resultJson, id
      )

    def putCode(approvalId: ApprovalId, codeHash: String, expiresAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "INSERT OR REPLACE INTO approval_codes (approval_id, code_hash, expires_at, used_at) VALUES (?, ?, ?, NULL)",
        approvalId, codeHash, expiresAt
      )

    def findCode(approvalId: ApprovalId): IO[AgentError, Option[ApprovalCode]] =
      db.queryOne("SELECT * FROM approval_codes WHERE approval_id = ?", approvalId)(rs =>
        ApprovalCode(col(rs, "code_hash"), instant(rs, "expires_at"), optInstant(rs, "used_at"))
      )

    def markCodeUsed(approvalId: ApprovalId, usedAt: Instant): IO[AgentError, Unit] =
      db.execute("UPDATE approval_codes SET used_at = ? WHERE approval_id = ?", usedAt, approvalId)
  }

  def sqliteAuditRepo(db: Sqlite): AuditRepo = new AuditRepo {
    def create(e: AuditEvent): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO audit_events (id, actor, action, category, target_type, target_id, text, payload_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        e.id, e.actor, e.action, e.category, e.targetType, e.targetId,
        e.text, e.payloadJson, e.createdAt
      )

    def list(category: Option[String], since: Option[Instant], until: Option[Instant], limit: Int): IO[AgentError, List[AuditEvent]] = {
      val clauses = List(
        Clause.of("category = ?")(category),
        Clause.of("created_at >= ?")(since),
        Clause.of("created_at < ?")(until)
      ).flatten
      val sql = s"SELECT * FROM audit_events${whereSql("WHERE", clauses)} ORDER BY created_at DESC LIMIT ?"
      db.query(sql, (paramsOf(clauses) :+ limit): _*)(extractEvent)
    }

    def searchFts(query: String, category: Option[String], since: Option[Instant], limit: Int): IO[AgentError, List[AuditEvent]] = {
      val clauses = Clause("audit_events_fts MATCH ?", List(query)) :: List(
        Clause.of("e.category = ?")(category),
        Clause.of("e.created_at >= ?")(since)
      ).flatten
      val sql =
        s"""SELECT e.* FROM audit_events_fts f
           | JOIN audit_events e ON e.rowid = f.rowid
           | WHERE ${clauses.map(_.sql).mkString(" AND ")}
           | ORDER BY bm25(audit_events_fts) LIMIT ?""".stripMargin
      db.query(sql, (paramsOf(clauses) :+ limit): _*)(extractEvent)
    }
  }

  def sqliteGoalRepo(db: Sqlite): GoalRepo = new GoalRepo {
    def create(g: Goal): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO goals (id, owner_person_id, title, outcome, evidence_rule, constraints_json, status, blocked_reason, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        g.id, g.ownerPersonId, g.title, g.outcome, g.evidenceRule,
        g.constraintsJson, GoalStatus.asString(g.status), g.blockedReason,
        g.source, g.createdAt, g.updatedAt
      )

    def findAll(ownerPersonId: Option[PersonId], status: Option[String]): IO[AgentError, List[Goal]] = {
      val clauses = List(
        Clause.of("owner_person_id = ?")(ownerPersonId),
        Clause.of("status = ?")(status)
      ).flatten
      val sql = s"SELECT * FROM goals${whereSql("WHERE", clauses)} ORDER BY created_at DESC"
      db.query(sql, paramsOf(clauses): _*)(extractGoal)
    }

    def findById(id: GoalId): IO[AgentError, Option[Goal]] =
      db.queryOne("SELECT * FROM goals WHERE id = ?", id)(extractGoal)

    def findBySource(ownerPersonId: PersonId, source: String): IO[AgentError, Option[Goal]] =
      db.queryOne(
        "SELECT * FROM goals WHERE owner_person_id = ? AND source = ? ORDER BY created_at DESC LIMIT 1",
        ownerPersonId, source
      )(extractGoal)

    def updateStatus(id: GoalId, status: GoalStatus, blockedReason: Option[String], updatedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE goals SET status = ?, blocked_reason = ?, updated_at = ? WHERE id = ?",
        GoalStatus.asString(status), blockedReason, updatedAt, id
      )

    /** Re-propose-by-source updates only mutable display fields; `outcome` and
     *  `evidence_rule` are immutable by design and are deliberately left alone. */
    def updateContent(id: GoalId, title: String, constraintsJson: Option[String], updatedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE goals SET title = ?, constraints_json = ?, updated_at = ? WHERE id = ?",
        title, constraintsJson, updatedAt, id
      )
  }

  def sqliteGoalEvidenceRepo(db: Sqlite): GoalEvidenceRepo = new GoalEvidenceRepo {
    def create(e: GoalEvidence): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO goal_evidence (id, goal_id, kind, ref, note, recorded_at) VALUES (?, ?, ?, ?, ?, ?)",
        e.id, e.goalId, e.kind, e.ref, e.note, e.recordedAt
      )

    def findByGoal(goalId: GoalId): IO[AgentError, List[GoalEvidence]] =
      db.query(
        "SELECT * FROM goal_evidence WHERE goal_id = ? ORDER BY recorded_at ASC",
        goalId
      )(extractGoalEvidence)
  }

  def sqliteEntityRepo(db: Sqlite): EntityRepo = new EntityRepo {
    def create(e: Entity): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO entities (id, kind, name, attributes_json, status, source, confidence, superseded_by_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        e.id, EntityKind.asString(e.kind), e.name, e.attributesJson, MemoryStatus.asString(e.status),
        e.source, e.confidence, e.supersededById, e.createdAt, e.updatedAt
      )

    def findById(id: EntityId): IO[AgentError, Option[Entity]] =
      db.queryOne("SELECT * FROM entities WHERE id = ?", id)(extractEntity)

    def findAll(kind: Option[String], status: Option[String]): IO[AgentError, List[Entity]] = {
      val clauses = List(
        Clause.of("kind = ?")(kind),
        Clause.of("status = ?")(status)
      ).flatten
      val sql = s"SELECT * FROM entities${whereSql("WHERE", clauses)} ORDER BY created_at DESC"
      db.query(sql, paramsOf(clauses): _*)(extractEntity)
    }

    /** Case-insensitive substring match for entity resolution. */
    def findByName(name: String, status: Option[String]): IO[AgentError, List[Entity]] = {
      val clauses = Clause("name LIKE ? COLLATE NOCASE", List("%" + name + "%")) :: List(
        Clause.of("status = ?")(status)
      ).flatten
      val sql = s"SELECT * FROM entities WHERE ${clauses.map(_.sql).mkString(" AND ")} ORDER BY created_at DESC"
      db.query(sql, paramsOf(clauses): _*)(extractEntity)
    }

    def updateStatus(id: EntityId, status: MemoryStatus, updatedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE entities SET status = ?, updated_at = ? WHERE id = ?",
        MemoryStatus.asString(status), updatedAt, id
      )

    def setSupersededBy(oldId: EntityId, newId: EntityId, updatedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE entities SET superseded_by_id = ?, updated_at = ? WHERE id = ?",
        newId, updatedAt, oldId
      )
  }

  def sqliteRelationshipRepo(db: Sqlite): RelationshipRepo = new RelationshipRepo {
    def create(r: Relationship): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO relationships (id, from_id, from_kind, rel_type, to_id, to_kind, status, source, confidence, note, superseded_by_id, valid_from, valid_until, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        r.id, r.fromId, NodeKind.asString(r.fromKind), r.relType, r.toId, NodeKind.asString(r.toKind),
        MemoryStatus.asString(r.status), r.source, r.confidence, r.note, r.supersededById,
        r.validFrom, r.validUntil, r.createdAt, r.updatedAt
      )

    def findById(id: RelationshipId): IO[AgentError, Option[Relationship]] =
      db.queryOne("SELECT * FROM relationships WHERE id = ?", id)(extractRelationship)

    /** `asOf` (when set) keeps only edges currently active at that instant
     *  (non-superseded, within valid_from/valid_until). With `None`, only the
     *  non-superseded edges are returned (validity is left to the caller). */
    def findAll(fromId: Option[String], toId: Option[String], relType: Option[String], status: Option[String], asOf: Option[Instant]): IO[AgentError, List[Relationship]] = {
      val asOfClauses = asOf match {
        case None     => List(Clause("superseded_by_id IS NULL"))
        case Some(ts) => List(
          Clause("superseded_by_id IS NULL"),
          Clause("(valid_from IS NULL OR valid_from <= ?)", List(ts)),
          Clause("(valid_until IS NULL OR valid_until > ?)", List(ts))
        )
      }
      val clauses = List(
        Clause.of("from_id = ?")(fromId),
        Clause.of("to_id = ?")(toId),
        Clause.of("rel_type = ?")(relType),
        Clause.of("status = ?")(status)
      ).flatten ::: asOfClauses
      val sql = s"SELECT * FROM relationships WHERE ${clauses.map(_.sql).mkString(" AND ")} ORDER BY created_at DESC"
      db.query(sql, paramsOf(clauses): _*)(extractRelationship)
    }

    def updateStatus(id: RelationshipId, status: MemoryStatus, updatedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE relationships SET status = ?, updated_at = ? WHERE id = ?",
        MemoryStatus.asString(status), updatedAt, id
      )

    def setSupersededBy(oldId: RelationshipId, newId: RelationshipId, updatedAt: Instant): IO[AgentError, Unit] =
      db.execute(
        "UPDATE relationships SET superseded_by_id = ?, updated_at = ? WHERE id = ?",
        newId, updatedAt, oldId
      )
  }

  def sqliteChannelRepo(db: Sqlite): ChannelRepo = new ChannelRepo {
    def create(c: Channel): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO channels (id, default_model, created_at) VALUES (?, ?, ?)",
        c.id, c.defaultModel, c.createdAt
      )

    def findAll: IO[AgentError, List[Channel]] =
      db.query("SELECT * FROM channels ORDER BY created_at DESC")(extractChannel)

    def findById(id: ChannelId): IO[AgentError, Option[Channel]] =
      db.queryOne("SELECT * FROM channels WHERE id = ?", id)(extractChannel)
  }

  def sqliteChannelMemberRepo(db: Sqlite): ChannelMemberRepo = new ChannelMemberRepo {
    def add(m: ChannelMember): IO[AgentError, Unit] =
      db.execute(
        "INSERT OR IGNORE INTO channel_members (channel_id, person_id) VALUES (?, ?)",
        m.channelId, m.personId
      )

    def findByChannel(channelId: ChannelId): IO[AgentError, List[ChannelMember]] =
      db.query("SELECT * FROM channel_members WHERE channel_id = ?", channelId)(extractChannelMember)
  }

  def sqliteMessageRepo(db: Sqlite): MessageRepo = new MessageRepo {
    def create(m: Message): IO[AgentError, Unit] =
      db.execute(
        "INSERT INTO messages (id, channel_id, role, person_id_from, content, tool_calls_json, external_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        m.id, m.channelId, MessageRole.asString(m.role), m.personIdFrom,
        m.content, m.toolCallsJson, m.externalId, m.createdAt
      )

    def findByChannel(channelId: ChannelId, since: Option[Instant], limit: Int): IO[AgentError, List[Message]] = {
      val clauses = Clause("channel_id = ?", List(channelId)) :: List(
        Clause.of("created_at >= ?")(since)
      ).flatten
      val sql = s"SELECT * FROM messages WHERE ${clauses.map(_.sql).mkString(" AND ")} ORDER BY created_at DESC LIMIT ?"
      db.query(sql, (paramsOf(clauses) :+ limit): _*)(extractMessage)
    }
  }

  def sqliteCredentialRepo(db: Sqlite): CredentialRepo = new CredentialRepo {
    def upsert(c: Credential): IO[AgentError, Unit] =
      db.execute(
        """INSERT INTO credentials (id, provider, account_email, owner_person_id, access_token, refresh_token, expires_at, scopes, updated_at)
          | VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          | ON CONFLICT(provider, owner_person_id) DO UPDATE SET
          |   account_email = excluded.account_email,
          |   access_token = excluded.access_token,
          |   refresh_token = excluded.refresh_token,
          |   expires_at = excluded.expires_at,
          |   scopes = excluded.scopes,
          |   updated_at = excluded.updated_at""".stripMargin,
        c.id, c.provider, c.accountEmail, c.ownerPersonId, c.accessToken, c.refreshToken,
        c.expiresAt, c.scopes, c.updatedAt
      )

    def findByOwner(provider: String, ownerPersonId: PersonId): IO[AgentError, Option[Credential]] =
      db.queryOne(
        "SELECT * FROM credentials WHERE provider = ? AND owner_person_id = ?",
        provider, ownerPersonId
      )(extractCredential)
  }

  def sqliteInboxMessageRepo(db: Sqlite): InboxMessageRepo = new InboxMessageRepo {
    def upsert(m: InboxMessage): IO[AgentError, Unit] =
      db.execute(
        """INSERT INTO inbox_messages (id, provider, external_id, thread_id, from_addr, subject, body_text,
          | received_at, owner_person_id, triage_status, triaged_at, source_event_id, attachments_json)
          | VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          | ON CONFLICT(provider, external_id, owner_person_id) DO NOTHING""".stripMargin,
        m.id, m.provider, m.externalId, m.threadId, m.fromAddr, m.subject, m.bodyText,
        m.receivedAt, m.ownerPersonId, TriageStatus.asString(m.triageStatus),
        m.triagedAt, m.sourceEventId, m.attachments.toJson
      )

    def findById(id: InboxMessageId): IO[AgentError, Option[InboxMessage]] =
      db.queryOne("SELECT * FROM inbox_messages WHERE id = ?", id)(extractInboxMessage)

    def findAll(ownerPersonId: Option[PersonId], status: Option[String], limit: Int, oldestFirst: Boolean = false): IO[AgentError, List[InboxMessage]] = {
      val clauses = List(
        Clause.of("owner_person_id = ?")(ownerPersonId),
        Clause.of("triage_status = ?")(status)
      ).flatten
      val order = if (oldestFirst) "ASC" else "DESC"
      val sql = s"SELECT * FROM inbox_messages${whereSql("WHERE", clauses)} ORDER BY received_at $order LIMIT ?"
      db.query(sql, (paramsOf(clauses) :+ limit): _*)(extractInboxMessage)
    }

    def existsExternal(provider: String, externalId: String, ownerPersonId: PersonId): IO[AgentError, Boolean] =
      db.queryOne(
        "SELECT 1 FROM inbox_messages WHERE provider = ? AND external_id = ? AND owner_person_id = ?",
        provider, externalId, ownerPersonId
      )(_ => 1).map(_.isDefined)

    def updateStatus(id: InboxMessageId, status: TriageStatus, triagedAt: Instant, sourceEventId: Option[EventId]): IO[AgentError, Unit] =
      db.execute(
        "UPDATE inbox_messages SET triage_status = ?, triaged_at = ?, source_event_id = ? WHERE id = ?",
        TriageStatus.asString(status), triagedAt, sourceEventId, id
      )

    def countPending(ownerPersonId: PersonId): IO[AgentError, Int] =
      db.queryOne(
        "SELECT COUNT(*) AS cnt FROM inbox_messages WHERE owner_person_id = ? AND triage_status = 'pending'",
        ownerPersonId
      )(_.getInt("cnt")).map(_.getOrElse(0))
  }
}
