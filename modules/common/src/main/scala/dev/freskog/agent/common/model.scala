package dev.freskog.agent.common

import java.time.Instant

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

sealed trait ScopeKind
object ScopeKind {
  case object Private   extends ScopeKind
  case object Shared    extends ScopeKind
  case object Work      extends ScopeKind
  case object Household extends ScopeKind
  case object School    extends ScopeKind
  case object Other     extends ScopeKind

  def fromString(s: String): Either[String, ScopeKind] = s.toLowerCase match {
    case "private"   => Right(Private)
    case "shared"    => Right(Shared)
    case "work"      => Right(Work)
    case "household" => Right(Household)
    case "school"    => Right(School)
    case "other"     => Right(Other)
    case _           => Left(s"Unknown scope kind: $s")
  }
}

sealed trait ScopeRole
object ScopeRole {
  case object Owner    extends ScopeRole
  case object Editor   extends ScopeRole
  case object Viewer   extends ScopeRole
  case object Proposer extends ScopeRole

  def fromString(s: String): Either[String, ScopeRole] = s.toLowerCase match {
    case "owner"    => Right(Owner)
    case "editor"   => Right(Editor)
    case "viewer"   => Right(Viewer)
    case "proposer" => Right(Proposer)
    case _          => Left(s"Unknown role: $s")
  }
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
  id: String,
  displayName: String,
  timezone: String,
  defaultLocale: Option[String],
  active: Boolean
)

case class Scope(
  id: String,
  name: String,
  ownerPersonId: Option[String],
  kind: ScopeKind
)

case class PersonScopeRole(
  personId: String,
  scopeId: String,
  role: ScopeRole
)

case class Commitment(
  id: String,
  ownerPersonId: String,
  scopeId: String,
  status: CommitmentStatus,
  text: String,
  source: String,
  evidence: String,
  dueAt: Option[Instant],
  createdAt: Instant,
  updatedAt: Instant
)

case class MemoryItem(
  id: String,
  personId: Option[String],
  scopeId: Option[String],
  status: MemoryStatus,
  kind: MemoryKind,
  text: String,
  source: String,
  confidence: Option[Double],
  createdAt: Instant,
  updatedAt: Instant
)

case class Approval(
  id: String,
  requestedBy: String,
  requiredPersonId: Option[String],
  scopeId: Option[String],
  actionType: String,
  payloadJson: String,
  status: ApprovalStatus,
  createdAt: Instant,
  decidedAt: Option[Instant]
)

case class AuditEvent(
  id: String,
  actor: String,
  action: String,
  targetType: String,
  targetId: Option[String],
  scopeId: Option[String],
  payloadJson: String,
  createdAt: Instant
)
