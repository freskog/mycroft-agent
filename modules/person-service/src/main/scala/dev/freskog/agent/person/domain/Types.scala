package dev.freskog.agent.person.domain

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio.json._

import java.time.Instant

// --- Request payloads ---

case class ProposeCommitmentRequest(
  ownerPersonId: PersonId,
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
  actionType: String,
  payloadJson: String,
  // Optional saga link: the skill (+ params) mycroft runs once this action
  // executes, letting a gated multi-step workflow resume from durable state.
  continuationSkill: Option[String] = None,
  continuationParams: Option[String] = None,
  // The conversation this arose from; the continuation/notification turn runs here.
  channel: Option[String] = None,
  // Provenance (e.g. `email:gmail-msg-X`) — surfaced to the human at decision time.
  source: Option[String] = None
)
object RequestApprovalRequest {
  implicit val codec: JsonCodec[RequestApprovalRequest] = DeriveJsonCodec.gen[RequestApprovalRequest]
}

/** A human's decision on a pending approval. `code` is the one-time decision code
 *  the human received out-of-band (never visible to the agent); it is the gate.
 *  `decidedBy` records who (validated against `requiredPersonId` when set);
 *  `approve=false` rejects. This is the trusted human action — never the agent. */
case class DecideApprovalRequest(
  code: String,
  decidedBy: Option[PersonId] = None,
  approve: Boolean,
  reason: Option[String] = None
)
object DecideApprovalRequest {
  implicit val codec: JsonCodec[DecideApprovalRequest] = DeriveJsonCodec.gen[DecideApprovalRequest]
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

/** Partial update of an existing person's mutable metadata; `id` comes from the
 *  path. Absent fields are left unchanged. Gateless (low-risk, reversible). */
case class UpdatePersonRequest(
  displayName: Option[String] = None,
  timezone: Option[String] = None,
  defaultLocale: Option[String] = None
)
object UpdatePersonRequest {
  implicit val codec: JsonCodec[UpdatePersonRequest] = DeriveJsonCodec.gen[UpdatePersonRequest]
}

case class ProposeGoalRequest(
  ownerPersonId: PersonId,
  title: String,
  outcome: String,
  evidenceRule: String,
  constraintsJson: Option[String],
  source: Option[String]
)
object ProposeGoalRequest {
  implicit val codec: JsonCodec[ProposeGoalRequest] = DeriveJsonCodec.gen[ProposeGoalRequest]
}

// --- Household graph (entities + relationships) ---

case class ProposeEntityRequest(
  kind: EntityKind,
  name: String,
  attributesJson: Option[String] = None,
  source: String,
  confidence: Option[Double] = None
)
object ProposeEntityRequest {
  implicit val codec: JsonCodec[ProposeEntityRequest] = DeriveJsonCodec.gen[ProposeEntityRequest]
}

case class ProposeRelationshipRequest(
  fromId: String,
  fromKind: NodeKind,
  relType: String,
  toId: String,
  toKind: NodeKind,
  source: String,
  confidence: Option[Double] = None,
  note: Option[String] = None,
  validFrom: Option[Instant] = None,
  validUntil: Option[Instant] = None
)
object ProposeRelationshipRequest {
  implicit val codec: JsonCodec[ProposeRelationshipRequest] = DeriveJsonCodec.gen[ProposeRelationshipRequest]
}

case class SupersedeEntityRequest(newId: EntityId, oldId: EntityId)
object SupersedeEntityRequest {
  implicit val codec: JsonCodec[SupersedeEntityRequest] = DeriveJsonCodec.gen[SupersedeEntityRequest]
}

case class SupersedeRelationshipRequest(newId: RelationshipId, oldId: RelationshipId)
object SupersedeRelationshipRequest {
  implicit val codec: JsonCodec[SupersedeRelationshipRequest] = DeriveJsonCodec.gen[SupersedeRelationshipRequest]
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
  targetType: Option[String],
  targetId: Option[String],
  text: Option[String],
  payloadJson: Option[String]
)
object LogEventRequest {
  implicit val codec: JsonCodec[LogEventRequest] = DeriveJsonCodec.gen[LogEventRequest]
}

case class ConsolidateRequest(since: Option[Instant])
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

case class GmailOAuthExchangeRequest(
  ownerPersonId: PersonId,
  code: String,
  redirectUri: String
)
object GmailOAuthExchangeRequest {
  implicit val codec: JsonCodec[GmailOAuthExchangeRequest] = DeriveJsonCodec.gen[GmailOAuthExchangeRequest]
}

case class GmailAuthUrlResponse(url: String, redirectUri: String)
object GmailAuthUrlResponse {
  implicit val codec: JsonCodec[GmailAuthUrlResponse] = DeriveJsonCodec.gen[GmailAuthUrlResponse]
}

case class GmailCredentialSummary(
  provider: String,
  accountEmail: String,
  ownerPersonId: PersonId,
  scopes: String
)
object GmailCredentialSummary {
  implicit val codec: JsonCodec[GmailCredentialSummary] = DeriveJsonCodec.gen[GmailCredentialSummary]
}

case class GmailSyncResult(fetched: Int, inserted: Int, pending: Int)
object GmailSyncResult {
  implicit val codec: JsonCodec[GmailSyncResult] = DeriveJsonCodec.gen[GmailSyncResult]
}

case class MarkInboxTriagedRequest(sourceEventId: Option[EventId] = None)
object MarkInboxTriagedRequest {
  implicit val codec: JsonCodec[MarkInboxTriagedRequest] = DeriveJsonCodec.gen[MarkInboxTriagedRequest]
}

/** One downloaded attachment. `dataBase64` is standard (not url-safe) base64 of
 *  the raw bytes — the CLI decodes it and writes the file locally. */
case class AttachmentDownload(
  filename: String,
  mimeType: String,
  sizeBytes: Long,
  dataBase64: String
)
object AttachmentDownload {
  implicit val codec: JsonCodec[AttachmentDownload] = DeriveJsonCodec.gen[AttachmentDownload]
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

