package dev.freskog.agent.person

import dev.freskog.agent.common.{Scope => PersonScope, _}
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.persistence._
import dev.freskog.agent.person.seed.SeedData
import dev.freskog.agent.person.service.PersonService

import zio._
import zio.test._

object PersonServiceSpec extends ZIOSpecDefault {

  private def withService(f: PersonService => ZIO[Any, Any, TestResult]): ZIO[Any, Any, TestResult] =
    ZIO.scoped {
      for {
        db <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
        _  <- Migrations.migrate(db)
        _  <- SeedData.seed(db)
        service = PersonService.live(
          Repos.sqlitePersonRepo(db),
          Repos.sqliteScopeRepo(db),
          Repos.sqliteScopeRoleRepo(db),
          Repos.sqliteCommitmentRepo(db),
          Repos.sqliteMemoryRepo(db),
          Repos.sqliteApprovalRepo(db),
          Repos.sqliteAuditRepo(db)
        )
        result <- f(service)
      } yield result
    }

  def spec = suite("PersonServiceSpec")(
    test("seeded persons are available") {
      withService { svc =>
        for {
          persons <- svc.listPersons
        } yield assertTrue(
          persons.length == 2,
          persons.exists(_.id == "fred"),
          persons.exists(_.id == "paula")
        )
      }
    },
    test("seeded scopes are available") {
      withService { svc =>
        for {
          scopes <- svc.listScopes
        } yield assertTrue(
          scopes.length == 4,
          scopes.exists(_.id == "fred_private"),
          scopes.exists(_.id == "family_shared")
        )
      }
    },
    test("propose commitment creates with proposed status") {
      withService { svc =>
        for {
          c <- svc.proposeCommitment(ProposeCommitmentRequest(
            ownerPersonId = "fred",
            scopeId = "fred_work",
            text = "Send Graham update by Friday",
            source = "email:gmail-msg-123",
            evidence = "Could you send me the update by Friday?",
            dueAt = None
          ))
        } yield assertTrue(
          c.status == CommitmentStatus.Proposed,
          c.ownerPersonId == "fred",
          c.scopeId == "fred_work",
          c.id.nonEmpty
        )
      }
    },
    test("list commitments filters by scope") {
      withService { svc =>
        for {
          _ <- svc.proposeCommitment(ProposeCommitmentRequest("fred", "fred_work", "task1", "src", "ev", None))
          _ <- svc.proposeCommitment(ProposeCommitmentRequest("fred", "fred_private", "task2", "src", "ev", None))
          workOnly <- svc.listCommitments(None, Some("fred_work"), None)
        } yield assertTrue(
          workOnly.length == 1,
          workOnly.head.text == "task1"
        )
      }
    },
    test("list commitments filters by status") {
      withService { svc =>
        for {
          _ <- svc.proposeCommitment(ProposeCommitmentRequest("fred", "fred_work", "task1", "src", "ev", None))
          proposed <- svc.listCommitments(None, None, Some("proposed"))
          open <- svc.listCommitments(None, None, Some("open"))
        } yield assertTrue(
          proposed.length == 1,
          open.isEmpty
        )
      }
    },
    test("propose memory creates with proposed status") {
      withService { svc =>
        for {
          m <- svc.proposeMemory(ProposeMemoryRequest(
            personId = Some("fred"),
            scopeId = Some("fred_work"),
            kind = MemoryKind.Preference,
            text = "Prefer draft-only email actions",
            source = "chat:local",
            confidence = Some(0.9)
          ))
        } yield assertTrue(
          m.status == MemoryStatus.Proposed,
          m.kind == MemoryKind.Preference,
          m.text == "Prefer draft-only email actions"
        )
      }
    },
    test("request approval creates with requested status") {
      withService { svc =>
        for {
          a <- svc.requestApproval(RequestApprovalRequest(
            requestedBy = "agent",
            requiredPersonId = Some("fred"),
            scopeId = Some("family_shared"),
            actionType = "calendar.propose_event",
            payloadJson = """{"summary":"family dinner"}"""
          ))
        } yield assertTrue(
          a.status == ApprovalStatus.Requested,
          a.actionType == "calendar.propose_event",
          a.decidedAt.isEmpty
        )
      }
    },
    test("list approvals filters by scope") {
      withService { svc =>
        for {
          _ <- svc.requestApproval(RequestApprovalRequest("agent", None, Some("family_shared"), "test", "{}"))
          _ <- svc.requestApproval(RequestApprovalRequest("agent", None, Some("fred_work"), "test2", "{}"))
          familyOnly <- svc.listApprovals(Some("family_shared"), None)
        } yield assertTrue(familyOnly.length == 1)
      }
    }
  )
}
