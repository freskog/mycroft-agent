package dev.freskog.agent.person

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.persistence._
import dev.freskog.agent.person.seed.SeedData
import dev.freskog.agent.person.seed.SeedData.{FredId, PaulaId}
import dev.freskog.agent.person.service.PersonService

import zio._
import zio.json._
import zio.test._

import java.time.Duration

/** The HITL approval lifecycle: propose (idempotent) → human decide (gated by a
 *  one-time code the agent never sees) → on approve the action executes
 *  server-side (`approval.ping` echoes; `goal.create` records a goal), records the
 *  result, and is idempotent on re-approve. */
object ApprovalSpec extends ZIOSpecDefault {

  private def withService(codeTtl: Duration = Duration.ofHours(48))(f: PersonService => ZIO[Any, AgentError, TestResult]): ZIO[Any, AgentError, TestResult] =
    ZIO.scoped {
      for {
        db      <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
        _       <- Migrations.migrate(db)
        _       <- SeedData.seed(db)
        hub     <- Hub.sliding[ApprovalEvent](16)
        service  = PersonService.live(
          Repos.sqlitePersonRepo(db), Repos.sqliteCommitmentRepo(db), Repos.sqliteMemoryRepo(db),
          Repos.sqliteApprovalRepo(db), Repos.sqliteAuditRepo(db), Repos.sqliteGoalRepo(db),
          Repos.sqliteGoalEvidenceRepo(db), Repos.sqliteEntityRepo(db), Repos.sqliteRelationshipRepo(db),
          Repos.sqliteChannelRepo(db), Repos.sqliteChannelMemberRepo(db), Repos.sqliteMessageRepo(db),
          Repos.sqliteCredentialRepo(db), Repos.sqliteInboxMessageRepo(db), Repos.sqliteCalendarEventRepo(db), Repos.sqliteBriefingRepo(db), hub, codeTtl
        )
        result  <- f(service)
      } yield result
    }

  private def ping(
    payload: String,
    required: Option[PersonId] = None,
    contSkill: Option[String] = None,
    contParams: Option[String] = None
  ): RequestApprovalRequest =
    RequestApprovalRequest("agent", required, "approval.ping", payload, contSkill, contParams, Some("fred"))

  /** Issue a one-time code (as a client on the private interface would) and decide. */
  private def decide(svc: PersonService, id: ApprovalId, approve: Boolean = true, by: Option[PersonId] = None, reason: Option[String] = None): IO[AgentError, Approval] =
    svc.issueDecisionCode(id).flatMap(code => svc.decideApproval(id, DecideApprovalRequest(code, by, approve, reason)))

  private def decideWithChoice(svc: PersonService, id: ApprovalId, chosen: Option[String]): IO[AgentError, Approval] =
    svc.issueDecisionCode(id).flatMap(code =>
      svc.decideApproval(id, DecideApprovalRequest(code, approve = true, chosenOptionId = chosen))
    )

  /** Like `withService`, but also exposes the approval repo so a test can insert an
   *  approval that already carries a (trusted-core-authored) options menu — options
   *  are never set via `requestApproval` for `approval.ping`, so we seed them here. */
  private def withRepo(f: (PersonService, ApprovalRepo) => ZIO[Any, AgentError, TestResult]): ZIO[Any, AgentError, TestResult] =
    ZIO.scoped {
      for {
        db      <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
        _       <- Migrations.migrate(db)
        _       <- SeedData.seed(db)
        hub     <- Hub.sliding[ApprovalEvent](16)
        approvalRepo = Repos.sqliteApprovalRepo(db)
        service  = PersonService.live(
          Repos.sqlitePersonRepo(db), Repos.sqliteCommitmentRepo(db), Repos.sqliteMemoryRepo(db),
          approvalRepo, Repos.sqliteAuditRepo(db), Repos.sqliteGoalRepo(db),
          Repos.sqliteGoalEvidenceRepo(db), Repos.sqliteEntityRepo(db), Repos.sqliteRelationshipRepo(db),
          Repos.sqliteChannelRepo(db), Repos.sqliteChannelMemberRepo(db), Repos.sqliteMessageRepo(db),
          Repos.sqliteCredentialRepo(db), Repos.sqliteInboxMessageRepo(db), Repos.sqliteCalendarEventRepo(db), Repos.sqliteBriefingRepo(db), hub
        )
        result  <- f(service, approvalRepo)
      } yield result
    }

  /** Insert a `requested` approval carrying an options menu. */
  private def seedWithOptions(repo: ApprovalRepo, payload: String, options: String): IO[AgentError, Approval] =
    for {
      now <- Clock.instant
      a    = Approval(
               ApprovalId(java.util.UUID.randomUUID().toString), "agent", None,
               "approval.ping", payload, ApprovalStatus.Requested, now, None,
               channel = Some("fred"), optionsJson = Some(options)
             )
      _   <- repo.create(a)
    } yield a

  def spec: Spec[Any, AgentError] = suite("ApprovalSpec")(

    test("propose creates a requested approval") {
      withService() { svc =>
        svc.requestApproval(ping("""{"x":1}""")).map(a =>
          assertTrue(a.status == ApprovalStatus.Requested, a.executedAt.isEmpty, a.id.value.nonEmpty)
        )
      }
    },

    test("propose is idempotent on identical requester+action+payload") {
      withService() { svc =>
        for {
          a <- svc.requestApproval(ping("""{"x":1}"""))
          b <- svc.requestApproval(ping("""{"x":1}"""))
        } yield assertTrue(a.id == b.id)
      }
    },

    test("approve (with a valid code) executes the action and records the result") {
      withService() { svc =>
        for {
          a <- svc.requestApproval(ping("""{"hello":"world"}"""))
          d <- decide(svc, a.id)
        } yield assertTrue(
          d.status == ApprovalStatus.Approved,
          d.executedAt.isDefined,
          d.resultJson.exists(_.contains("approval.ping")),
          d.resultJson.exists(_.contains("\"hello\":\"world\""))
        )
      }
    },

    test("a wrong code is rejected and the action does not execute") {
      withService() { svc =>
        for {
          a   <- svc.requestApproval(ping("""{"n":9}"""))
          _   <- svc.issueDecisionCode(a.id)
          bad <- svc.decideApproval(a.id, DecideApprovalRequest("not-the-code", approve = true)).either
          now <- svc.getApproval(a.id)
        } yield assertTrue(bad.isLeft, now.exists(_.status == ApprovalStatus.Requested), now.exists(_.executedAt.isEmpty))
      }
    },

    test("an expired code is rejected") {
      withService(Duration.ofSeconds(-1)) { svc =>
        for {
          a   <- svc.requestApproval(ping("""{"n":10}"""))
          out <- decide(svc, a.id).either
        } yield assertTrue(out.isLeft)
      }
    },

    test("deciding without first issuing a code fails") {
      withService() { svc =>
        for {
          a   <- svc.requestApproval(ping("""{"n":11}"""))
          out <- svc.decideApproval(a.id, DecideApprovalRequest("anything", approve = true)).either
        } yield assertTrue(out.isLeft)
      }
    },

    test("the one-time code never appears on an agent-readable surface") {
      withService() { svc =>
        for {
          a    <- svc.requestApproval(ping("""{"n":12}"""))
          code <- svc.issueDecisionCode(a.id)
          got  <- svc.getApproval(a.id)
          list <- svc.listApprovals(None)
        } yield assertTrue(
          !got.map(_.toJson).getOrElse("").contains(code),
          !list.toJson.contains(code)
        )
      }
    },

    test("re-approving an executed approval is idempotent (no re-execute)") {
      withService() { svc =>
        for {
          a  <- svc.requestApproval(ping("""{"n":1}"""))
          d1 <- decide(svc, a.id)
          // A second decision attempt: already executed → returns the stored result.
          d2 <- svc.decideApproval(a.id, DecideApprovalRequest("ignored", approve = true))
        } yield assertTrue(d2.executedAt == d1.executedAt, d2.resultJson == d1.resultJson)
      }
    },

    test("reject changes status without executing") {
      withService() { svc =>
        for {
          a <- svc.requestApproval(ping("""{"n":2}"""))
          d <- decide(svc, a.id, approve = false, reason = Some("nope"))
        } yield assertTrue(d.status == ApprovalStatus.Rejected, d.executedAt.isEmpty, d.resultJson.isEmpty)
      }
    },

    test("a targeted approval must be decided by the required person") {
      withService() { svc =>
        for {
          a       <- svc.requestApproval(ping("""{"n":3}""", required = Some(FredId)))
          noBy    <- decide(svc, a.id, by = None).either
          wrongBy <- decide(svc, a.id, by = Some(PaulaId)).either
          rightBy <- decide(svc, a.id, by = Some(FredId))
        } yield assertTrue(
          noBy.isLeft, wrongBy.isLeft,
          rightBy.status == ApprovalStatus.Approved, rightBy.executedAt.isDefined
        )
      }
    },

    test("goal.create executes into a durable goal on approval") {
      withService() { svc =>
        val payload =
          """{"ownerPersonId":"fred","title":"Approve Q3 report","outcome":"PR merged to main","evidenceRule":"commit hash on main","constraintsJson":null,"source":"email:msg-1"}"""
        for {
          a     <- svc.requestApproval(RequestApprovalRequest("agent", Some(FredId), "goal.create", payload, None, None, Some("fred")))
          d     <- decide(svc, a.id, by = Some(FredId))
          goals <- svc.listGoals(Some(FredId), None)
        } yield assertTrue(
          d.executedAt.isDefined,
          d.resultJson.exists(_.contains("Approve Q3 report")),
          goals.exists(g => g.title == "Approve Q3 report" && g.status == GoalStatus.Open)
        )
      }
    },

    test("the continuation (skill + params) round-trips on the approval") {
      withService() { svc =>
        for {
          a <- svc.requestApproval(ping("""{"n":4}""", contSkill = Some("offsite-planner"), contParams = Some("""{"phase":"confirm"}""")))
          g <- svc.getApproval(a.id)
        } yield assertTrue(
          g.flatMap(_.continuationSkill).contains("offsite-planner"),
          g.flatMap(_.continuationParams).contains("""{"phase":"confirm"}"""),
          g.flatMap(_.channel).contains("fred")
        )
      }
    },

    test("approve emits an executed event on the stream") {
      ZIO.scoped {
        for {
          db      <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
          _       <- Migrations.migrate(db)
          _       <- SeedData.seed(db)
          hub     <- Hub.sliding[ApprovalEvent](16)
          svc      = PersonService.live(
            Repos.sqlitePersonRepo(db), Repos.sqliteCommitmentRepo(db), Repos.sqliteMemoryRepo(db),
            Repos.sqliteApprovalRepo(db), Repos.sqliteAuditRepo(db), Repos.sqliteGoalRepo(db),
            Repos.sqliteGoalEvidenceRepo(db), Repos.sqliteEntityRepo(db), Repos.sqliteRelationshipRepo(db),
            Repos.sqliteChannelRepo(db), Repos.sqliteChannelMemberRepo(db), Repos.sqliteMessageRepo(db),
            Repos.sqliteCredentialRepo(db), Repos.sqliteInboxMessageRepo(db), Repos.sqliteCalendarEventRepo(db), Repos.sqliteBriefingRepo(db), hub
          )
          sub     <- hub.subscribe
          a       <- svc.requestApproval(ping("""{"n":5}"""))
          code    <- svc.issueDecisionCode(a.id)
          _       <- svc.decideApproval(a.id, DecideApprovalRequest(code, approve = true))
          events  <- sub.takeAll
        } yield assertTrue(
          events.exists(_.kind == "requested"),
          events.exists(e => e.kind == "executed" && e.approval.id == a.id)
        )
      }
    },

    test("listApprovals accepts 'pending' as an alias for 'requested'") {
      withService() { svc =>
        for {
          a       <- svc.requestApproval(ping("""{"n":42}"""))
          pending <- svc.listApprovals(Some("pending"))
          reqd    <- svc.listApprovals(Some("requested"))
        } yield assertTrue(
          pending.exists(_.id == a.id),
          pending.map(_.id.value) == reqd.map(_.id.value)
        )
      }
    },

    test("a chosen option's params merge into the payload before execution") {
      withRepo { (svc, repo) =>
        val options = """[{"id":"a","label":"A","params":{"x":1}},{"id":"b","label":"B","params":{"x":2}}]"""
        for {
          a <- seedWithOptions(repo, """{"base":true}""", options)
          d <- decideWithChoice(svc, a.id, Some("a"))
        } yield assertTrue(
          d.executedAt.isDefined,
          // The echoed payload (approval.ping echoes it) carries the base field and
          // the merged option param — proof the chosen option entered the payload.
          d.resultJson.exists(_.contains("\"base\":true")),
          d.resultJson.exists(_.contains("\"x\":1")),
          !d.resultJson.exists(_.contains("\"x\":2"))
        )
      }
    },

    test("approving a multi-option approval without a valid choice is rejected") {
      withRepo { (svc, repo) =>
        val options = """[{"id":"a","label":"A","params":{"x":1}},{"id":"b","label":"B","params":{"x":2}}]"""
        for {
          a       <- seedWithOptions(repo, """{"base":true}""", options)
          noChoice <- decideWithChoice(svc, a.id, None).either
          b       <- seedWithOptions(repo, """{"base":true}""", options)
          badChoice <- decideWithChoice(svc, b.id, Some("zzz")).either
          still   <- svc.getApproval(a.id)
        } yield assertTrue(
          noChoice.isLeft, badChoice.isLeft,
          // The action did not execute when the choice was missing/invalid.
          still.exists(_.executedAt.isEmpty)
        )
      }
    },

    test("a single-option approval needs no explicit choice (auto-picked)") {
      withRepo { (svc, repo) =>
        val options = """[{"id":"only","label":"Only","params":{"x":9}}]"""
        for {
          a <- seedWithOptions(repo, """{"base":true}""", options)
          d <- decideWithChoice(svc, a.id, None)
        } yield assertTrue(d.executedAt.isDefined, d.resultJson.exists(_.contains("\"x\":9")))
      }
    },

    test("optionsJson round-trips on the approval") {
      withRepo { (svc, repo) =>
        val options = """[{"id":"a","label":"A","params":{"x":1}}]"""
        for {
          a <- seedWithOptions(repo, """{"base":true}""", options)
          g <- svc.getApproval(a.id)
        } yield assertTrue(g.flatMap(_.optionsJson).contains(options))
      }
    },

    test("DecideApprovalRequest codec round-trips chosenOptionId") {
      val req = DecideApprovalRequest("the-code", Some(FredId), approve = true, reason = Some("ok"), chosenOptionId = Some("a"))
      assertTrue(req.toJson.fromJson[DecideApprovalRequest] == Right(req))
    },

    test("a repeated (deduped) request re-publishes 'requested' so it can re-surface") {
      ZIO.scoped {
        for {
          db      <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
          _       <- Migrations.migrate(db)
          _       <- SeedData.seed(db)
          hub     <- Hub.sliding[ApprovalEvent](16)
          svc      = PersonService.live(
            Repos.sqlitePersonRepo(db), Repos.sqliteCommitmentRepo(db), Repos.sqliteMemoryRepo(db),
            Repos.sqliteApprovalRepo(db), Repos.sqliteAuditRepo(db), Repos.sqliteGoalRepo(db),
            Repos.sqliteGoalEvidenceRepo(db), Repos.sqliteEntityRepo(db), Repos.sqliteRelationshipRepo(db),
            Repos.sqliteChannelRepo(db), Repos.sqliteChannelMemberRepo(db), Repos.sqliteMessageRepo(db),
            Repos.sqliteCredentialRepo(db), Repos.sqliteInboxMessageRepo(db), Repos.sqliteCalendarEventRepo(db), Repos.sqliteBriefingRepo(db), hub
          )
          sub     <- hub.subscribe
          a       <- svc.requestApproval(ping("""{"n":7}"""))
          b       <- svc.requestApproval(ping("""{"n":7}"""))  // identical → deduped
          events  <- sub.takeAll
        } yield assertTrue(
          a.id == b.id,
          events.count(_.kind == "requested") == 2  // once on create, once on the repeat ask
        )
      }
    }
  )
}
