package dev.freskog.agent.person.domain

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio.json._

import java.time.Instant

// --- Request payloads ---

case class ProposeCommitmentRequest(
  ownerPersonId: PersonId,
  scopeId: ScopeId,
  text: String,
  source: String,
  evidence: String,
  dueAt: Option[Instant]
)
object ProposeCommitmentRequest {
  implicit val codec: JsonCodec[ProposeCommitmentRequest] = DeriveJsonCodec.gen[ProposeCommitmentRequest]
}

case class ProposeMemoryRequest(
  personId: Option[PersonId],
  scopeId: Option[ScopeId],
  kind: MemoryKind,
  text: String,
  source: String,
  confidence: Option[Double],
  validFrom: Option[Instant] = None,
  validUntil: Option[Instant] = None,
  originEventId: Option[EventId] = None
)
object ProposeMemoryRequest {
  implicit val codec: JsonCodec[ProposeMemoryRequest] = DeriveJsonCodec.gen[ProposeMemoryRequest]
}

case class RequestApprovalRequest(
  requestedBy: String,
  requiredPersonId: Option[PersonId],
  scopeId: Option[ScopeId],
  actionType: String,
  payloadJson: String
)
object RequestApprovalRequest {
  implicit val codec: JsonCodec[RequestApprovalRequest] = DeriveJsonCodec.gen[RequestApprovalRequest]
}

case class CreatePersonRequest(
  id: PersonId,
  displayName: String,
  timezone: String,
  defaultLocale: Option[String]
)
object CreatePersonRequest {
  implicit val codec: JsonCodec[CreatePersonRequest] = DeriveJsonCodec.gen[CreatePersonRequest]
}

case class CreateScopeRequest(
  id: ScopeId,
  name: String,
  ownerPersonId: Option[PersonId],
  kind: ScopeKind
)
object CreateScopeRequest {
  implicit val codec: JsonCodec[CreateScopeRequest] = DeriveJsonCodec.gen[CreateScopeRequest]
}

case class CreateScopeRoleRequest(
  personId: PersonId,
  scopeId: ScopeId,
  role: ScopeRole
)
object CreateScopeRoleRequest {
  implicit val codec: JsonCodec[CreateScopeRoleRequest] = DeriveJsonCodec.gen[CreateScopeRoleRequest]
}

case class ProposeGoalRequest(
  ownerPersonId: PersonId,
  scopeId: ScopeId,
  title: String,
  outcome: String,
  evidenceRule: String,
  constraintsJson: Option[String],
  source: Option[String]
)
object ProposeGoalRequest {
  implicit val codec: JsonCodec[ProposeGoalRequest] = DeriveJsonCodec.gen[ProposeGoalRequest]
}

case class UpdateGoalStatusRequest(
  status: GoalStatus,
  blockedReason: Option[String]
)
object UpdateGoalStatusRequest {
  implicit val codec: JsonCodec[UpdateGoalStatusRequest] = DeriveJsonCodec.gen[UpdateGoalStatusRequest]
}

case class AppendGoalEvidenceRequest(
  kind: String,
  ref: String,
  note: Option[String]
)
object AppendGoalEvidenceRequest {
  implicit val codec: JsonCodec[AppendGoalEvidenceRequest] = DeriveJsonCodec.gen[AppendGoalEvidenceRequest]
}

case class RejectMemoryRequest(reason: Option[String])
object RejectMemoryRequest {
  implicit val codec: JsonCodec[RejectMemoryRequest] = DeriveJsonCodec.gen[RejectMemoryRequest]
}

case class SupersedeMemoryRequest(newId: MemoryId, oldId: MemoryId)
object SupersedeMemoryRequest {
  implicit val codec: JsonCodec[SupersedeMemoryRequest] = DeriveJsonCodec.gen[SupersedeMemoryRequest]
}

case class LogEventRequest(
  actor: String,
  action: String,
  category: String,
  scopeId: Option[ScopeId],
  targetType: Option[String],
  targetId: Option[String],
  text: Option[String],
  payloadJson: Option[String]
)
object LogEventRequest {
  implicit val codec: JsonCodec[LogEventRequest] = DeriveJsonCodec.gen[LogEventRequest]
}

case class ConsolidateRequest(scopeId: ScopeId, since: Option[Instant])
object ConsolidateRequest {
  implicit val codec: JsonCodec[ConsolidateRequest] = DeriveJsonCodec.gen[ConsolidateRequest]
}

case class CreateChannelRequest(
  id: ChannelId,
  defaultModel: Option[String],
  members: List[PersonId] = Nil
)
object CreateChannelRequest {
  implicit val codec: JsonCodec[CreateChannelRequest] = DeriveJsonCodec.gen[CreateChannelRequest]
}

case class AddMemberRequest(personId: PersonId)
object AddMemberRequest {
  implicit val codec: JsonCodec[AddMemberRequest] = DeriveJsonCodec.gen[AddMemberRequest]
}

case class AppendMessageRequest(
  channelId: ChannelId,
  role: MessageRole,
  personIdFrom: Option[PersonId],
  content: String,
  toolCallsJson: Option[String] = None,
  externalId: Option[String] = None
)
object AppendMessageRequest {
  implicit val codec: JsonCodec[AppendMessageRequest] = DeriveJsonCodec.gen[AppendMessageRequest]
}

// --- Audit payload codecs ---

/** Recorded on `goal.status.*` / `memory.reject` events. */
case class StatusChangePayload(reason: Option[String])
object StatusChangePayload {
  implicit val codec: JsonCodec[StatusChangePayload] = DeriveJsonCodec.gen[StatusChangePayload]
  val empty: StatusChangePayload                     = StatusChangePayload(None)
}

/** Recorded on `memory.supersede`. */
case class SupersedePayload(newId: MemoryId)
object SupersedePayload {
  implicit val codec: JsonCodec[SupersedePayload] = DeriveJsonCodec.gen[SupersedePayload]
}

/** Recorded on `goal.evidence.append`. */
case class GoalEvidencePayload(kind: String, ref: String)
object GoalEvidencePayload {
  implicit val codec: JsonCodec[GoalEvidencePayload] = DeriveJsonCodec.gen[GoalEvidencePayload]
}

/** Recorded on `memory.consolidate` with the count proposed. */
case class ConsolidatePayload(proposed: Int)
object ConsolidatePayload {
  implicit val codec: JsonCodec[ConsolidatePayload] = DeriveJsonCodec.gen[ConsolidatePayload]
}
