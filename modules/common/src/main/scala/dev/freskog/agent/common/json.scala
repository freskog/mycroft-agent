package dev.freskog.agent.common

import zio.json._

import java.time.Instant

object JsonCodecs {

  // --- Instant codec ---
  implicit val instantEncoder: JsonEncoder[Instant] =
    JsonEncoder.string.contramap(_.toString)

  implicit val instantDecoder: JsonDecoder[Instant] =
    JsonDecoder.string.mapOrFail { s =>
      try Right(Instant.parse(s))
      catch { case e: Exception => Left(s"Invalid instant: ${e.getMessage}") }
    }

  // --- Shell ---
  implicit val shellEncoder: JsonEncoder[Shell] =
    JsonEncoder.string.contramap {
      case Shell.Bash => "bash"
      case Shell.Zsh  => "zsh"
      case Shell.Sh   => "sh"
    }

  implicit val shellDecoder: JsonDecoder[Shell] =
    JsonDecoder.string.mapOrFail(Shell.fromString)

  // --- ExitStatus ---
  implicit val exitStatusEncoder: JsonEncoder[ExitStatus] =
    JsonEncoder[zio.json.ast.Json].contramap {
      case ExitStatus.Exited(code)   => zio.json.ast.Json.Obj("type" -> zio.json.ast.Json.Str("exited"), "code" -> zio.json.ast.Json.Num(code))
      case ExitStatus.Killed(signal) => zio.json.ast.Json.Obj("type" -> zio.json.ast.Json.Str("killed"), "signal" -> zio.json.ast.Json.Str(signal))
      case ExitStatus.TimedOut       => zio.json.ast.Json.Obj("type" -> zio.json.ast.Json.Str("timed_out"))
      case ExitStatus.Failed(reason) => zio.json.ast.Json.Obj("type" -> zio.json.ast.Json.Str("failed"), "reason" -> zio.json.ast.Json.Str(reason))
    }

  implicit val exitStatusDecoder: JsonDecoder[ExitStatus] =
    JsonDecoder[zio.json.ast.Json].mapOrFail { json =>
      json match {
        case zio.json.ast.Json.Obj(fields) =>
          val map = fields.toMap
          map.get("type").flatMap {
            case zio.json.ast.Json.Str("exited") =>
              map.get("code").collect { case zio.json.ast.Json.Num(n) => ExitStatus.Exited(n.intValue) }
            case zio.json.ast.Json.Str("killed") =>
              map.get("signal").collect { case zio.json.ast.Json.Str(s) => ExitStatus.Killed(s) }
            case zio.json.ast.Json.Str("timed_out") =>
              Some(ExitStatus.TimedOut)
            case zio.json.ast.Json.Str("failed") =>
              map.get("reason").collect { case zio.json.ast.Json.Str(r) => ExitStatus.Failed(r) }
            case _ => None
          }.toRight("Invalid ExitStatus JSON")
        case _ => Left("ExitStatus must be a JSON object")
      }
    }

  // --- NodeKind ---
  implicit val nodeKindEncoder: JsonEncoder[NodeKind] =
    JsonEncoder.string.contramap(NodeKind.asString)

  implicit val nodeKindDecoder: JsonDecoder[NodeKind] =
    JsonDecoder.string.mapOrFail(NodeKind.fromString)

  // --- EntityKind ---
  implicit val entityKindEncoder: JsonEncoder[EntityKind] =
    JsonEncoder.string.contramap(EntityKind.asString)

  implicit val entityKindDecoder: JsonDecoder[EntityKind] =
    JsonDecoder.string.mapOrFail(EntityKind.fromString)

  // --- CommitmentStatus ---
  implicit val commitmentStatusEncoder: JsonEncoder[CommitmentStatus] =
    JsonEncoder.string.contramap {
      case CommitmentStatus.Proposed  => "proposed"
      case CommitmentStatus.Open      => "open"
      case CommitmentStatus.Done      => "done"
      case CommitmentStatus.Ignored   => "ignored"
      case CommitmentStatus.Cancelled => "cancelled"
    }

  implicit val commitmentStatusDecoder: JsonDecoder[CommitmentStatus] =
    JsonDecoder.string.mapOrFail(CommitmentStatus.fromString)

  // --- MemoryStatus ---
  implicit val memoryStatusEncoder: JsonEncoder[MemoryStatus] =
    JsonEncoder.string.contramap {
      case MemoryStatus.Proposed => "proposed"
      case MemoryStatus.Accepted => "accepted"
      case MemoryStatus.Rejected => "rejected"
      case MemoryStatus.Archived => "archived"
    }

  implicit val memoryStatusDecoder: JsonDecoder[MemoryStatus] =
    JsonDecoder.string.mapOrFail(MemoryStatus.fromString)

  // --- MemoryKind ---
  implicit val memoryKindEncoder: JsonEncoder[MemoryKind] =
    JsonEncoder.string.contramap {
      case MemoryKind.Preference    => "preference"
      case MemoryKind.Fact          => "fact"
      case MemoryKind.ProjectNote   => "project_note"
      case MemoryKind.ProcedureNote => "procedure_note"
    }

  implicit val memoryKindDecoder: JsonDecoder[MemoryKind] =
    JsonDecoder.string.mapOrFail(MemoryKind.fromString)

  // --- TrustLevel ---
  implicit val trustLevelEncoder: JsonEncoder[TrustLevel] =
    JsonEncoder.string.contramap(TrustLevel.asString)

  implicit val trustLevelDecoder: JsonDecoder[TrustLevel] =
    JsonDecoder.string.mapOrFail(TrustLevel.fromString)

  // --- ApprovalStatus ---
  implicit val approvalStatusEncoder: JsonEncoder[ApprovalStatus] =
    JsonEncoder.string.contramap {
      case ApprovalStatus.Requested => "requested"
      case ApprovalStatus.Approved  => "approved"
      case ApprovalStatus.Rejected  => "rejected"
      case ApprovalStatus.Expired   => "expired"
    }

  implicit val approvalStatusDecoder: JsonDecoder[ApprovalStatus] =
    JsonDecoder.string.mapOrFail(ApprovalStatus.fromString)

  // --- GoalStatus ---
  implicit val goalStatusEncoder: JsonEncoder[GoalStatus] =
    JsonEncoder.string.contramap(GoalStatus.asString)

  implicit val goalStatusDecoder: JsonDecoder[GoalStatus] =
    JsonDecoder.string.mapOrFail(GoalStatus.fromString)

  // --- AgentError (encoder only; errors flow outward) ---
  implicit val agentErrorEncoder: JsonEncoder[AgentError] = {
    import zio.json.ast.Json
    JsonEncoder[Json].contramap {
      case e @ AgentError.NotFound(target, id) =>
        Json.Obj(
          "type"       -> Json.Str("not_found"),
          "targetType" -> Json.Str(target),
          "id"         -> Json.Str(id),
          "message"    -> Json.Str(e.message)
        )
      case AgentError.BadRequest(m) =>
        Json.Obj("type" -> Json.Str("bad_request"), "message" -> Json.Str(m))
      case AgentError.Validation(m) =>
        Json.Obj("type" -> Json.Str("validation"), "message" -> Json.Str(m))
      case AgentError.Persistence(m, _) =>
        Json.Obj("type" -> Json.Str("persistence"), "message" -> Json.Str(m))
      case AgentError.HttpFailed(m, _) =>
        Json.Obj("type" -> Json.Str("http_failed"), "message" -> Json.Str(m))
      case AgentError.HttpBadStatus(m, s, body) =>
        Json.Obj(
          "type"    -> Json.Str("http_bad_status"),
          "message" -> Json.Str(m),
          "status"  -> Json.Num(s),
          "body"    -> Json.Str(body)
        )
      case AgentError.DecodeFailed(m, _) =>
        Json.Obj("type" -> Json.Str("decode_failed"), "message" -> Json.Str(m))
      case AgentError.Bug(m, _) =>
        Json.Obj("type" -> Json.Str("bug"), "message" -> Json.Str(m))
    }
  }

  // --- Newtype IDs (bare-string codecs) ---
  private def idCodec[A](wrap: String => A, unwrap: A => String): JsonCodec[A] =
    JsonCodec(
      JsonEncoder.string.contramap(unwrap),
      JsonDecoder.string.map(wrap)
    )

  implicit val personIdCodec: JsonCodec[PersonId]               = idCodec(PersonId.apply, _.value)
  implicit val memoryIdCodec: JsonCodec[MemoryId]               = idCodec(MemoryId.apply, _.value)
  implicit val goalIdCodec: JsonCodec[GoalId]                   = idCodec(GoalId.apply, _.value)
  implicit val eventIdCodec: JsonCodec[EventId]                 = idCodec(EventId.apply, _.value)
  implicit val commitmentIdCodec: JsonCodec[CommitmentId]       = idCodec(CommitmentId.apply, _.value)
  implicit val approvalIdCodec: JsonCodec[ApprovalId]           = idCodec(ApprovalId.apply, _.value)
  implicit val goalEvidenceIdCodec: JsonCodec[GoalEvidenceId]   = idCodec(GoalEvidenceId.apply, _.value)
  implicit val credentialIdCodec: JsonCodec[CredentialId]       = idCodec(CredentialId.apply, _.value)
  implicit val inboxMessageIdCodec: JsonCodec[InboxMessageId]   = idCodec(InboxMessageId.apply, _.value)
  implicit val calendarEventIdCodec: JsonCodec[CalendarEventId] = idCodec(CalendarEventId.apply, _.value)
  implicit val channelIdCodec: JsonCodec[ChannelId]             = idCodec(ChannelId.apply, _.value)
  implicit val messageIdCodec: JsonCodec[MessageId]             = idCodec(MessageId.apply, _.value)
  implicit val entityIdCodec: JsonCodec[EntityId]               = idCodec(EntityId.apply, _.value)
  implicit val relationshipIdCodec: JsonCodec[RelationshipId]   = idCodec(RelationshipId.apply, _.value)

  implicit val triageStatusEncoder: JsonEncoder[TriageStatus] =
    JsonEncoder.string.contramap(TriageStatus.asString)

  implicit val triageStatusDecoder: JsonDecoder[TriageStatus] =
    JsonDecoder.string.mapOrFail(TriageStatus.fromString)

  // --- MessageRole ---
  implicit val messageRoleEncoder: JsonEncoder[MessageRole] =
    JsonEncoder.string.contramap(MessageRole.asString)

  implicit val messageRoleDecoder: JsonDecoder[MessageRole] =
    JsonDecoder.string.mapOrFail(MessageRole.fromString)

  // --- Domain types ---
  implicit val runMetadataCodec: JsonCodec[RunMetadata]     = DeriveJsonCodec.gen[RunMetadata]
  implicit val errorResponseCodec: JsonCodec[ErrorResponse] = DeriveJsonCodec.gen[ErrorResponse]
  implicit val personCodec: JsonCodec[Person]               = DeriveJsonCodec.gen[Person]
  implicit val entityCodec: JsonCodec[Entity]               = DeriveJsonCodec.gen[Entity]
  implicit val relationshipCodec: JsonCodec[Relationship]   = DeriveJsonCodec.gen[Relationship]
  implicit val householdGraphCodec: JsonCodec[HouseholdGraph] = DeriveJsonCodec.gen[HouseholdGraph]
  implicit val commitmentCodec: JsonCodec[Commitment]       = DeriveJsonCodec.gen[Commitment]
  implicit val memoryItemCodec: JsonCodec[MemoryItem]       = DeriveJsonCodec.gen[MemoryItem]
  implicit val approvalCodec: JsonCodec[Approval]           = DeriveJsonCodec.gen[Approval]
  implicit val approvalEventCodec: JsonCodec[ApprovalEvent] = DeriveJsonCodec.gen[ApprovalEvent]
  implicit val auditEventCodec: JsonCodec[AuditEvent]       = DeriveJsonCodec.gen[AuditEvent]
  implicit val goalCodec: JsonCodec[Goal]                   = DeriveJsonCodec.gen[Goal]
  implicit val goalEvidenceCodec: JsonCodec[GoalEvidence]   = DeriveJsonCodec.gen[GoalEvidence]
  implicit val goalWithEvidenceCodec: JsonCodec[GoalWithEvidence] = DeriveJsonCodec.gen[GoalWithEvidence]
  implicit val memoryHitCodec: JsonCodec[MemoryHit]         = DeriveJsonCodec.gen[MemoryHit]
  implicit val eventHitCodec: JsonCodec[EventHit]           = DeriveJsonCodec.gen[EventHit]
  implicit val contextBundleCodec: JsonCodec[ContextBundle] = DeriveJsonCodec.gen[ContextBundle]
  implicit val channelCodec: JsonCodec[Channel]             = DeriveJsonCodec.gen[Channel]
  implicit val channelMemberCodec: JsonCodec[ChannelMember] = DeriveJsonCodec.gen[ChannelMember]
  implicit val channelWithMembersCodec: JsonCodec[ChannelWithMembers] = DeriveJsonCodec.gen[ChannelWithMembers]
  implicit val messageCodec: JsonCodec[Message]             = DeriveJsonCodec.gen[Message]
  implicit val credentialCodec: JsonCodec[Credential]       = DeriveJsonCodec.gen[Credential]
  implicit val inboxAttachmentCodec: JsonCodec[InboxAttachment] = DeriveJsonCodec.gen[InboxAttachment]
  implicit val inboxMessageCodec: JsonCodec[InboxMessage]   = DeriveJsonCodec.gen[InboxMessage]
  implicit val inboxSummaryCodec: JsonCodec[InboxSummary]   = DeriveJsonCodec.gen[InboxSummary]
  implicit val calendarEventCodec: JsonCodec[CalendarEvent] = DeriveJsonCodec.gen[CalendarEvent]
}
