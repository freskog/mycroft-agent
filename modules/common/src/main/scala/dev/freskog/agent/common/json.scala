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

  // --- ScopeKind ---
  implicit val scopeKindEncoder: JsonEncoder[ScopeKind] =
    JsonEncoder.string.contramap {
      case ScopeKind.Private   => "private"
      case ScopeKind.Shared    => "shared"
      case ScopeKind.Work      => "work"
      case ScopeKind.Household => "household"
      case ScopeKind.School    => "school"
      case ScopeKind.Other     => "other"
    }

  implicit val scopeKindDecoder: JsonDecoder[ScopeKind] =
    JsonDecoder.string.mapOrFail(ScopeKind.fromString)

  // --- ScopeRole ---
  implicit val scopeRoleEncoder: JsonEncoder[ScopeRole] =
    JsonEncoder.string.contramap {
      case ScopeRole.Owner    => "owner"
      case ScopeRole.Editor   => "editor"
      case ScopeRole.Viewer   => "viewer"
      case ScopeRole.Proposer => "proposer"
    }

  implicit val scopeRoleDecoder: JsonDecoder[ScopeRole] =
    JsonDecoder.string.mapOrFail(ScopeRole.fromString)

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

  // --- Domain types ---
  implicit val runMetadataCodec: JsonCodec[RunMetadata]     = DeriveJsonCodec.gen[RunMetadata]
  implicit val errorResponseCodec: JsonCodec[ErrorResponse] = DeriveJsonCodec.gen[ErrorResponse]
  implicit val personCodec: JsonCodec[Person]               = DeriveJsonCodec.gen[Person]
  implicit val scopeCodec: JsonCodec[Scope]                 = DeriveJsonCodec.gen[Scope]
  implicit val personScopeRoleCodec: JsonCodec[PersonScopeRole] = DeriveJsonCodec.gen[PersonScopeRole]
  implicit val commitmentCodec: JsonCodec[Commitment]       = DeriveJsonCodec.gen[Commitment]
  implicit val memoryItemCodec: JsonCodec[MemoryItem]       = DeriveJsonCodec.gen[MemoryItem]
  implicit val approvalCodec: JsonCodec[Approval]           = DeriveJsonCodec.gen[Approval]
  implicit val auditEventCodec: JsonCodec[AuditEvent]       = DeriveJsonCodec.gen[AuditEvent]
}
