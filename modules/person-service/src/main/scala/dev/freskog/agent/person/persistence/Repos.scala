package dev.freskog.agent.person.persistence

import dev.freskog.agent.common.{Scope => PersonScope, _}

import zio._

import java.time.Instant

trait PersonRepo {
  def create(person: Person): Task[Unit]
  def findAll: Task[List[Person]]
  def findById(id: String): Task[Option[Person]]
}

trait ScopeRepo {
  def create(scope: PersonScope): Task[Unit]
  def findAll: Task[List[PersonScope]]
}

trait ScopeRoleRepo {
  def create(role: PersonScopeRole): Task[Unit]
  def findByPerson(personId: String): Task[List[PersonScopeRole]]
}

trait CommitmentRepo {
  def create(commitment: Commitment): Task[Unit]
  def findAll(ownerPersonId: Option[String], scopeId: Option[String], status: Option[String]): Task[List[Commitment]]
}

trait MemoryRepo {
  def create(item: MemoryItem): Task[Unit]
  def findAll(personId: Option[String], scopeId: Option[String]): Task[List[MemoryItem]]
}

trait ApprovalRepo {
  def create(approval: Approval): Task[Unit]
  def findAll(scopeId: Option[String], status: Option[String]): Task[List[Approval]]
}

trait AuditRepo {
  def create(event: AuditEvent): Task[Unit]
}

object Repos {

  def sqlitePersonRepo(db: Sqlite): PersonRepo = new PersonRepo {
    def create(p: Person): Task[Unit] =
      db.execute(
        "INSERT INTO persons (id, display_name, timezone, default_locale, active) VALUES (?, ?, ?, ?, ?)",
        p.id, p.displayName, p.timezone, p.defaultLocale.orNull, if (p.active) 1 else 0
      )

    def findAll: Task[List[Person]] =
      db.query("SELECT * FROM persons") { rs =>
        Person(
          id = rs.getString("id"),
          displayName = rs.getString("display_name"),
          timezone = rs.getString("timezone"),
          defaultLocale = Option(rs.getString("default_locale")),
          active = rs.getInt("active") != 0
        )
      }

    def findById(id: String): Task[Option[Person]] =
      db.queryOne("SELECT * FROM persons WHERE id = ?", id) { rs =>
        Person(
          id = rs.getString("id"),
          displayName = rs.getString("display_name"),
          timezone = rs.getString("timezone"),
          defaultLocale = Option(rs.getString("default_locale")),
          active = rs.getInt("active") != 0
        )
      }
  }

  def sqliteScopeRepo(db: Sqlite): ScopeRepo = new ScopeRepo {
    def create(s: PersonScope): Task[Unit] = {
      val kindStr = s.kind match {
        case ScopeKind.Private   => "private"
        case ScopeKind.Shared    => "shared"
        case ScopeKind.Work      => "work"
        case ScopeKind.Household => "household"
        case ScopeKind.School    => "school"
        case ScopeKind.Other     => "other"
      }
      db.execute(
        "INSERT INTO scopes (id, name, owner_person_id, kind) VALUES (?, ?, ?, ?)",
        s.id, s.name, s.ownerPersonId.orNull, kindStr
      )
    }

    def findAll: Task[List[PersonScope]] =
      db.query("SELECT * FROM scopes") { rs =>
        PersonScope(
          id = rs.getString("id"),
          name = rs.getString("name"),
          ownerPersonId = Option(rs.getString("owner_person_id")),
          kind = ScopeKind.fromString(rs.getString("kind")).getOrElse(ScopeKind.Other)
        )
      }
  }

  def sqliteScopeRoleRepo(db: Sqlite): ScopeRoleRepo = new ScopeRoleRepo {
    def create(r: PersonScopeRole): Task[Unit] = {
      val roleStr = r.role match {
        case ScopeRole.Owner    => "owner"
        case ScopeRole.Editor   => "editor"
        case ScopeRole.Viewer   => "viewer"
        case ScopeRole.Proposer => "proposer"
      }
      db.execute(
        "INSERT INTO person_scope_roles (person_id, scope_id, role) VALUES (?, ?, ?)",
        r.personId, r.scopeId, roleStr
      )
    }

    def findByPerson(personId: String): Task[List[PersonScopeRole]] =
      db.query("SELECT * FROM person_scope_roles WHERE person_id = ?", personId) { rs =>
        PersonScopeRole(
          personId = rs.getString("person_id"),
          scopeId = rs.getString("scope_id"),
          role = ScopeRole.fromString(rs.getString("role")).getOrElse(ScopeRole.Viewer)
        )
      }
  }

  def sqliteCommitmentRepo(db: Sqlite): CommitmentRepo = new CommitmentRepo {
    def create(c: Commitment): Task[Unit] = {
      val statusStr = c.status match {
        case CommitmentStatus.Proposed  => "proposed"
        case CommitmentStatus.Open      => "open"
        case CommitmentStatus.Done      => "done"
        case CommitmentStatus.Ignored   => "ignored"
        case CommitmentStatus.Cancelled => "cancelled"
      }
      db.execute(
        "INSERT INTO commitments (id, owner_person_id, scope_id, status, text, source, evidence, due_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        c.id, c.ownerPersonId, c.scopeId, statusStr, c.text, c.source, c.evidence,
        c.dueAt.map(_.toString).orNull, c.createdAt.toString, c.updatedAt.toString
      )
    }

    def findAll(ownerPersonId: Option[String], scopeId: Option[String], status: Option[String]): Task[List[Commitment]] = {
      val filters = List(
        ownerPersonId.map(v => ("owner_person_id = ?", v)),
        scopeId.map(v => ("scope_id = ?", v)),
        status.map(v => ("status = ?", v))
      ).flatten

      val where = if (filters.isEmpty) "" else " WHERE " + filters.map(_._1).mkString(" AND ")
      val params = filters.map(_._2)

      db.query(s"SELECT * FROM commitments$where ORDER BY created_at DESC", params: _*) { rs =>
        Commitment(
          id = rs.getString("id"),
          ownerPersonId = rs.getString("owner_person_id"),
          scopeId = rs.getString("scope_id"),
          status = CommitmentStatus.fromString(rs.getString("status")).getOrElse(CommitmentStatus.Proposed),
          text = rs.getString("text"),
          source = rs.getString("source"),
          evidence = rs.getString("evidence"),
          dueAt = Option(rs.getString("due_at")).map(Instant.parse),
          createdAt = Instant.parse(rs.getString("created_at")),
          updatedAt = Instant.parse(rs.getString("updated_at"))
        )
      }
    }
  }

  def sqliteMemoryRepo(db: Sqlite): MemoryRepo = new MemoryRepo {
    def create(m: MemoryItem): Task[Unit] = {
      val statusStr = m.status match {
        case MemoryStatus.Proposed => "proposed"
        case MemoryStatus.Accepted => "accepted"
        case MemoryStatus.Rejected => "rejected"
        case MemoryStatus.Archived => "archived"
      }
      val kindStr = m.kind match {
        case MemoryKind.Preference    => "preference"
        case MemoryKind.Fact          => "fact"
        case MemoryKind.ProjectNote   => "project_note"
        case MemoryKind.ProcedureNote => "procedure_note"
      }
      db.execute(
        "INSERT INTO memory_items (id, person_id, scope_id, status, kind, text, source, confidence, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        m.id, m.personId.orNull, m.scopeId.orNull, statusStr, kindStr, m.text, m.source,
        m.confidence.map(java.lang.Double.valueOf).orNull, m.createdAt.toString, m.updatedAt.toString
      )
    }

    def findAll(personId: Option[String], scopeId: Option[String]): Task[List[MemoryItem]] = {
      val filters = List(
        personId.map(v => ("person_id = ?", v)),
        scopeId.map(v => ("scope_id = ?", v))
      ).flatten

      val where = if (filters.isEmpty) "" else " WHERE " + filters.map(_._1).mkString(" AND ")
      val params = filters.map(_._2)

      db.query(s"SELECT * FROM memory_items$where ORDER BY created_at DESC", params: _*) { rs =>
        MemoryItem(
          id = rs.getString("id"),
          personId = Option(rs.getString("person_id")),
          scopeId = Option(rs.getString("scope_id")),
          status = MemoryStatus.fromString(rs.getString("status")).getOrElse(MemoryStatus.Proposed),
          kind = MemoryKind.fromString(rs.getString("kind")).getOrElse(MemoryKind.Fact),
          text = rs.getString("text"),
          source = rs.getString("source"),
          confidence = Option(rs.getObject("confidence")).map(_.asInstanceOf[java.lang.Number].doubleValue()),
          createdAt = Instant.parse(rs.getString("created_at")),
          updatedAt = Instant.parse(rs.getString("updated_at"))
        )
      }
    }
  }

  def sqliteApprovalRepo(db: Sqlite): ApprovalRepo = new ApprovalRepo {
    def create(a: Approval): Task[Unit] = {
      val statusStr = a.status match {
        case ApprovalStatus.Requested => "requested"
        case ApprovalStatus.Approved  => "approved"
        case ApprovalStatus.Rejected  => "rejected"
        case ApprovalStatus.Expired   => "expired"
      }
      db.execute(
        "INSERT INTO approvals (id, requested_by, required_person_id, scope_id, action_type, payload_json, status, created_at, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        a.id, a.requestedBy, a.requiredPersonId.orNull, a.scopeId.orNull,
        a.actionType, a.payloadJson, statusStr, a.createdAt.toString, a.decidedAt.map(_.toString).orNull
      )
    }

    def findAll(scopeId: Option[String], status: Option[String]): Task[List[Approval]] = {
      val filters = List(
        scopeId.map(v => ("scope_id = ?", v)),
        status.map(v => ("status = ?", v))
      ).flatten

      val where = if (filters.isEmpty) "" else " WHERE " + filters.map(_._1).mkString(" AND ")
      val params = filters.map(_._2)

      db.query(s"SELECT * FROM approvals$where ORDER BY created_at DESC", params: _*) { rs =>
        Approval(
          id = rs.getString("id"),
          requestedBy = rs.getString("requested_by"),
          requiredPersonId = Option(rs.getString("required_person_id")),
          scopeId = Option(rs.getString("scope_id")),
          actionType = rs.getString("action_type"),
          payloadJson = rs.getString("payload_json"),
          status = ApprovalStatus.fromString(rs.getString("status")).getOrElse(ApprovalStatus.Requested),
          createdAt = Instant.parse(rs.getString("created_at")),
          decidedAt = Option(rs.getString("decided_at")).map(Instant.parse)
        )
      }
    }
  }

  def sqliteAuditRepo(db: Sqlite): AuditRepo = new AuditRepo {
    def create(e: AuditEvent): Task[Unit] =
      db.execute(
        "INSERT INTO audit_events (id, actor, action, target_type, target_id, scope_id, payload_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        e.id, e.actor, e.action, e.targetType, e.targetId.orNull, e.scopeId.orNull, e.payloadJson, e.createdAt.toString
      )
  }
}
