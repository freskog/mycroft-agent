package dev.freskog.agent.person.domain

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio.json._

import java.time.Instant

case class ProposeCommitmentRequest(
  ownerPersonId: String,
  scopeId: String,
  text: String,
  source: String,
  evidence: String,
  dueAt: Option[Instant]
)
object ProposeCommitmentRequest {
  implicit val codec: JsonCodec[ProposeCommitmentRequest] = DeriveJsonCodec.gen[ProposeCommitmentRequest]
}

case class ProposeMemoryRequest(
  personId: Option[String],
  scopeId: Option[String],
  kind: MemoryKind,
  text: String,
  source: String,
  confidence: Option[Double]
)
object ProposeMemoryRequest {
  implicit val codec: JsonCodec[ProposeMemoryRequest] = DeriveJsonCodec.gen[ProposeMemoryRequest]
}

case class RequestApprovalRequest(
  requestedBy: String,
  requiredPersonId: Option[String],
  scopeId: Option[String],
  actionType: String,
  payloadJson: String
)
object RequestApprovalRequest {
  implicit val codec: JsonCodec[RequestApprovalRequest] = DeriveJsonCodec.gen[RequestApprovalRequest]
}

case class CreatePersonRequest(
  id: String,
  displayName: String,
  timezone: String,
  defaultLocale: Option[String]
)
object CreatePersonRequest {
  implicit val codec: JsonCodec[CreatePersonRequest] = DeriveJsonCodec.gen[CreatePersonRequest]
}

case class CreateScopeRequest(
  id: String,
  name: String,
  ownerPersonId: Option[String],
  kind: ScopeKind
)
object CreateScopeRequest {
  implicit val codec: JsonCodec[CreateScopeRequest] = DeriveJsonCodec.gen[CreateScopeRequest]
}

case class CreateScopeRoleRequest(
  personId: String,
  scopeId: String,
  role: ScopeRole
)
object CreateScopeRoleRequest {
  implicit val codec: JsonCodec[CreateScopeRoleRequest] = DeriveJsonCodec.gen[CreateScopeRoleRequest]
}
