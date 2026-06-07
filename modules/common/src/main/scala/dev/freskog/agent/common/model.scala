package dev.freskog.agent.common

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

/** Canonical ISO-8601 timestamp formatter with fixed nanosecond precision so
 *  values stored as TEXT compare lexicographically against each other.
 *  `Instant.toString` is variable-precision and would break SQL `<=` ordering. */
object Time {
  private val Iso = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
    .withZone(ZoneOffset.UTC)

  def format(i: Instant): String = Iso.format(i)
}

sealed trait Shell
object Shell {
  case object Bash extends Shell
  case object Zsh  extends Shell
  case object Sh   extends Shell

  def fromString(s: String): Either[String, Shell] = s.toLowerCase match {
    case "bash" => Right(Bash)
    case "zsh"  => Right(Zsh)
    case "sh"   => Right(Sh)
    case other  => Left(s"Unsupported shell: $other. Supported: bash, zsh, sh")
  }

  def toCommand(shell: Shell): String = shell match {
    case Bash => "bash"
    case Zsh  => "zsh"
    case Sh   => "sh"
  }
}

sealed trait ExitStatus
object ExitStatus {
  case class Exited(code: Int)      extends ExitStatus
  case class Killed(signal: String) extends ExitStatus
  case object TimedOut              extends ExitStatus
  case class Failed(reason: String) extends ExitStatus
}

case class RunMetadata(
  runId: String,
  command: String,
  shell: String,
  cwd: String,
  startedAt: Instant,
  finishedAt: Instant,
  durationMs: Long,
  exitCode: Option[Int],
  timedOut: Boolean,
  termination: Option[String],
  stdoutBytes: Long,
  stderrBytes: Long,
  stdoutHead: String,
  stdoutTail: String,
  stderrHead: String,
  stderrTail: String,
  stdoutLog: String,
  stderrLog: String,
  metadataLog: String
)

case class ErrorResponse(
  error: String,
  detail: Option[String]
)

// --- Person-service domain types shared between service and CLI ---

/** Zero-cost newtype wrappers prevent accidentally swapping `PersonId` for
 *  `EntityId` etc. through method signatures. JSON encoding is bare-string
 *  (see JsonCodecs). */
final case class PersonId(value: String)        extends AnyVal
final case class MemoryId(value: String)         extends AnyVal
final case class GoalId(value: String)           extends AnyVal
final case class EventId(value: String)          extends AnyVal
final case class CommitmentId(value: String)     extends AnyVal
final case class ApprovalId(value: String)       extends AnyVal
final case class GoalEvidenceId(value: String)   extends AnyVal
final case class ChannelId(value: String)        extends AnyVal
final case class MessageId(value: String)        extends AnyVal
final case class EntityId(value: String)         extends AnyVal
final case class RelationshipId(value: String)   extends AnyVal

/** Whether a graph edge endpoint is a `person` (a `persons` row) or an
 *  `entity` (an `entities` row). Edges are polymorphic; `fromKind`/`toKind`
 *  disambiguate the id. */
sealed trait NodeKind
object NodeKind {
  case object Person extends NodeKind
  case object Entity extends NodeKind

  def fromString(s: String): Either[String, NodeKind] = s.toLowerCase match {
    case "person" => Right(Person)
    case "entity" => Right(Entity)
    case _        => Left(s"Unknown node kind: $s")
  }

  def asString(k: NodeKind): String = k match {
    case Person => "person"
    case Entity => "entity"
  }
}

/** The kind of a non-person node in the household graph (the things a person
 *  relates to). Deliberately a small open-ish set; `Other` is the catch-all. */
sealed trait EntityKind
object EntityKind {
  case object Organization extends EntityKind
  case object School       extends EntityKind
  case object Club         extends EntityKind
  case object Medical      extends EntityKind
  case object Vehicle      extends EntityKind
  case object Place        extends EntityKind
  case object Other        extends EntityKind

  def fromString(s: String): Either[String, EntityKind] = s.toLowerCase match {
    case "organization" | "org" => Right(Organization)
    case "school"               => Right(School)
    case "club"                 => Right(Club)
    case "medical"              => Right(Medical)
    case "vehicle"              => Right(Vehicle)
    case "place"                => Right(Place)
    case "other"                => Right(Other)
    case _                      => Left(s"Unknown entity kind: $s")
  }

  def asString(k: EntityKind): String = k match {
    case Organization => "organization"
    case School       => "school"
    case Club         => "club"
    case Medical      => "medical"
    case Vehicle      => "vehicle"
    case Place        => "place"
    case Other        => "other"
  }
}

/** Common relationship type constants. Stored as a free string (not a closed
 *  enum) so onboarding can mint household-specific edges without a code change;
 *  these are the well-known ones the playbooks lean on. */
object RelationshipType {
  val Spouse    = "spouse"
  val ParentOf  = "parent_of"
  val ChildOf   = "child_of"
  val EmployedBy = "employed_by"
  val Attends   = "attends"
  val PatientOf = "patient_of"
  val MemberOf  = "member_of"
  val Owns      = "owns"
  val LivesIn   = "lives_in"
}

sealed trait CommitmentStatus
object CommitmentStatus {
  case object Proposed  extends CommitmentStatus
  case object Open      extends CommitmentStatus
  case object Done      extends CommitmentStatus
  case object Ignored   extends CommitmentStatus
  case object Cancelled extends CommitmentStatus

  def fromString(s: String): Either[String, CommitmentStatus] = s.toLowerCase match {
    case "proposed"  => Right(Proposed)
    case "open"      => Right(Open)
    case "done"      => Right(Done)
    case "ignored"   => Right(Ignored)
    case "cancelled" => Right(Cancelled)
    case _           => Left(s"Unknown commitment status: $s")
  }
}

sealed trait MemoryStatus
object MemoryStatus {
  case object Proposed extends MemoryStatus
  case object Accepted extends MemoryStatus
  case object Rejected extends MemoryStatus
  case object Archived extends MemoryStatus

  def fromString(s: String): Either[String, MemoryStatus] = s.toLowerCase match {
    case "proposed" => Right(Proposed)
    case "accepted" => Right(Accepted)
    case "rejected" => Right(Rejected)
    case "archived" => Right(Archived)
    case _          => Left(s"Unknown memory status: $s")
  }

  def asString(s: MemoryStatus): String = s match {
    case Proposed => "proposed"
    case Accepted => "accepted"
    case Rejected => "rejected"
    case Archived => "archived"
  }
}

sealed trait MemoryKind
object MemoryKind {
  case object Preference    extends MemoryKind
  case object Fact          extends MemoryKind
  case object ProjectNote   extends MemoryKind
  case object ProcedureNote extends MemoryKind

  def fromString(s: String): Either[String, MemoryKind] = s.toLowerCase match {
    case "preference"      => Right(Preference)
    case "fact"            => Right(Fact)
    case "project_note"    => Right(ProjectNote)
    case "procedure_note"  => Right(ProcedureNote)
    case _                 => Left(s"Unknown memory kind: $s")
  }

  def asString(k: MemoryKind): String = k match {
    case Preference    => "preference"
    case Fact          => "fact"
    case ProjectNote   => "project_note"
    case ProcedureNote => "procedure_note"
  }
}

object EventCategory {
  val State       = "state"
  val Observation = "observation"
  val Utterance   = "utterance"
  val Decision    = "decision"
  val SessionNote = "session_note"

  val All: Set[String] = Set(State, Observation, Utterance, Decision, SessionNote)
}

sealed trait ApprovalStatus
object ApprovalStatus {
  case object Requested extends ApprovalStatus
  case object Approved  extends ApprovalStatus
  case object Rejected  extends ApprovalStatus
  case object Expired   extends ApprovalStatus

  def fromString(s: String): Either[String, ApprovalStatus] = s.toLowerCase match {
    case "requested" => Right(Requested)
    case "approved"  => Right(Approved)
    case "rejected"  => Right(Rejected)
    case "expired"   => Right(Expired)
    case _           => Left(s"Unknown approval status: $s")
  }
}

case class Person(
  id: PersonId,
  displayName: String,
  timezone: String,
  defaultLocale: Option[String],
  active: Boolean
)

/** A non-person node in the household graph (employer, school, club, GP, car,
 *  …). Carries the same propose→accept lifecycle and provenance as memory so a
 *  human accepts before it becomes durable. */
case class Entity(
  id: EntityId,
  kind: EntityKind,
  name: String,
  attributesJson: Option[String],
  status: MemoryStatus,
  source: String,
  confidence: Option[Double],
  supersededById: Option[EntityId] = None,
  createdAt: Instant,
  updatedAt: Instant
)

/** A typed edge in the household graph: person↔person (spouse, parent_of) or
 *  person↔entity (employed_by, attends). Time-bound (`validFrom`/`validUntil`)
 *  and supersedable so "transitions" (new job, new school) preserve history. */
case class Relationship(
  id: RelationshipId,
  fromId: String,
  fromKind: NodeKind,
  relType: String,
  toId: String,
  toKind: NodeKind,
  status: MemoryStatus,
  source: String,
  confidence: Option[Double],
  note: Option[String] = None,
  supersededById: Option[RelationshipId] = None,
  validFrom: Option[Instant] = None,
  validUntil: Option[Instant] = None,
  createdAt: Instant,
  updatedAt: Instant
)

/** The accepted household graph, as injected into context. */
case class HouseholdGraph(entities: List[Entity], relationships: List[Relationship])

case class Commitment(
  id: CommitmentId,
  ownerPersonId: PersonId,
  status: CommitmentStatus,
  text: String,
  source: String,
  evidence: String,
  dueAt: Option[Instant],
  createdAt: Instant,
  updatedAt: Instant
)

case class MemoryItem(
  id: MemoryId,
  personId: Option[PersonId],
  status: MemoryStatus,
  kind: MemoryKind,
  text: String,
  source: String,
  confidence: Option[Double],
  createdAt: Instant,
  updatedAt: Instant,
  supersededById: Option[MemoryId] = None,
  validFrom: Option[Instant] = None,
  validUntil: Option[Instant] = None,
  originEventId: Option[EventId] = None
)

case class Approval(
  id: ApprovalId,
  requestedBy: String,
  requiredPersonId: Option[PersonId],
  actionType: String,
  payloadJson: String,
  status: ApprovalStatus,
  createdAt: Instant,
  decidedAt: Option[Instant]
)

/** Note: `targetId` is intentionally a `String` — it's a polymorphic foreign
 *  key whose interpretation depends on `targetType`. `actor` is also a String
 *  because it can be `"agent"`, a person id, or another synthetic principal. */
case class AuditEvent(
  id: EventId,
  actor: String,
  action: String,
  category: String,
  targetType: String,
  targetId: Option[String],
  text: Option[String],
  payloadJson: String,
  createdAt: Instant
)

case class MemoryHit(item: MemoryItem, score: Double)

case class EventHit(event: AuditEvent, score: Double)

case class ContextBundle(facts: List[MemoryHit], events: List[AuditEvent])

sealed trait GoalStatus
object GoalStatus {
  case object Open      extends GoalStatus
  case object Blocked   extends GoalStatus
  case object Done      extends GoalStatus
  case object Cancelled extends GoalStatus

  def fromString(s: String): Either[String, GoalStatus] = s.toLowerCase match {
    case "open"      => Right(Open)
    case "blocked"   => Right(Blocked)
    case "done"      => Right(Done)
    case "cancelled" => Right(Cancelled)
    case _           => Left(s"Unknown goal status: $s")
  }

  def asString(g: GoalStatus): String = g match {
    case Open      => "open"
    case Blocked   => "blocked"
    case Done      => "done"
    case Cancelled => "cancelled"
  }
}

case class Goal(
  id: GoalId,
  ownerPersonId: PersonId,
  title: String,
  outcome: String,
  evidenceRule: String,
  constraintsJson: Option[String],
  status: GoalStatus,
  blockedReason: Option[String],
  source: Option[String],
  createdAt: Instant,
  updatedAt: Instant
)

case class GoalEvidence(
  id: GoalEvidenceId,
  goalId: GoalId,
  kind: String,
  ref: String,
  note: Option[String],
  recordedAt: Instant
)

case class GoalWithEvidence(
  goal: Goal,
  evidence: List[GoalEvidence]
)

// --- Inbox / credentials (email ingestion) ---

final case class CredentialId(value: String)    extends AnyVal
final case class InboxMessageId(value: String) extends AnyVal
final case class CalendarEventId(value: String) extends AnyVal

sealed trait TriageStatus
object TriageStatus {
  case object Pending extends TriageStatus
  case object Triaged extends TriageStatus
  case object Skipped extends TriageStatus

  def fromString(s: String): Either[String, TriageStatus] = s.toLowerCase match {
    case "pending" => Right(Pending)
    case "triaged" => Right(Triaged)
    case "skipped" => Right(Skipped)
    case _         => Left(s"Unknown triage status: $s")
  }

  def asString(t: TriageStatus): String = t match {
    case Pending => "pending"
    case Triaged => "triaged"
    case Skipped => "skipped"
  }
}

case class Credential(
  id: CredentialId,
  provider: String,
  accountEmail: String,
  ownerPersonId: PersonId,
  accessToken: String,
  refreshToken: String,
  expiresAt: Instant,
  scopes: String,
  updatedAt: Instant
)

/** Metadata for one attachment on an inbox message. The bytes are NOT stored;
 *  `attachmentId` is the provider-side handle used to download on demand. */
case class InboxAttachment(
  attachmentId: String,
  filename: String,
  mimeType: String,
  sizeBytes: Long
)

case class InboxMessage(
  id: InboxMessageId,
  provider: String,
  externalId: String,
  threadId: Option[String],
  fromAddr: String,
  subject: String,
  bodyText: String,
  receivedAt: Instant,
  ownerPersonId: PersonId,
  triageStatus: TriageStatus,
  triagedAt: Option[Instant],
  sourceEventId: Option[EventId],
  attachments: List[InboxAttachment] = Nil
)

/** A calendar event as read from the provider (Google Calendar). Read-only in
 *  Phase 1 — not persisted, returned live from an agenda query. `allDay` events
 *  carry date-only boundaries (`start`/`end` are the start-of-day instants in the
 *  event's own timezone, already normalised to `Instant`). */
case class CalendarEvent(
  externalId: String,
  calendarId: String,
  summary: String,
  start: Instant,
  end: Instant,
  allDay: Boolean,
  location: Option[String],
  description: Option[String],
  htmlLink: Option[String],
  status: String
)

// --- Channels & messages (mycroft / harness state) ---

sealed trait MessageRole
object MessageRole {
  case object User      extends MessageRole
  case object Assistant extends MessageRole
  case object Tool      extends MessageRole
  case object System    extends MessageRole

  def fromString(s: String): Either[String, MessageRole] = s.toLowerCase match {
    case "user"      => Right(User)
    case "assistant" => Right(Assistant)
    case "tool"      => Right(Tool)
    case "system"    => Right(System)
    case _           => Left(s"Unknown message role: $s")
  }

  def asString(r: MessageRole): String = r match {
    case User      => "user"
    case Assistant => "assistant"
    case Tool      => "tool"
    case System    => "system"
  }
}

case class Channel(
  id: ChannelId,
  defaultModel: Option[String],
  createdAt: Instant
)

case class ChannelMember(
  channelId: ChannelId,
  personId: PersonId
)

case class ChannelWithMembers(
  channel: Channel,
  members: List[PersonId]
)

case class Message(
  id: MessageId,
  channelId: ChannelId,
  role: MessageRole,
  personIdFrom: Option[PersonId],
  content: String,
  toolCallsJson: Option[String],
  externalId: Option[String],
  createdAt: Instant
)
