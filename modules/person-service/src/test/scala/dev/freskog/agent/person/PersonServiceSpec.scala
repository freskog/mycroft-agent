package dev.freskog.agent.person

import dev.freskog.agent.common._
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.persistence._
import dev.freskog.agent.person.seed.SeedData
import dev.freskog.agent.person.seed.SeedData.{FredId, PaulaId, SampleGoalId}
import dev.freskog.agent.person.service.PersonService

import zio._
import zio.test._

object PersonServiceSpec extends ZIOSpecDefault {

  private def withService(f: PersonService => ZIO[Any, AgentError, TestResult]): ZIO[Any, AgentError, TestResult] =
    ZIO.scoped {
      for {
        db      <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
        _       <- Migrations.migrate(db)
        _       <- SeedData.seed(db)
        hub     <- Hub.sliding[ApprovalEvent](16)
        service  = PersonService.live(
          Repos.sqlitePersonRepo(db),
          Repos.sqliteCommitmentRepo(db),
          Repos.sqliteMemoryRepo(db),
          Repos.sqliteApprovalRepo(db),
          Repos.sqliteAuditRepo(db),
          Repos.sqliteGoalRepo(db),
          Repos.sqliteGoalEvidenceRepo(db),
          Repos.sqliteEntityRepo(db),
          Repos.sqliteRelationshipRepo(db),
          Repos.sqliteChannelRepo(db),
          Repos.sqliteChannelMemberRepo(db),
          Repos.sqliteMessageRepo(db),
          Repos.sqliteCredentialRepo(db),
          Repos.sqliteInboxMessageRepo(db),
          hub
        )
        result  <- f(service)
      } yield result
    }

  // Memory is gateless: a propose is immediately accepted, so these helpers just
  // write the fact.
  private def proposeAndAcceptMemory(svc: PersonService, text: String, kind: MemoryKind = MemoryKind.Fact, confidence: Double = 0.7): IO[AgentError, MemoryItem] =
    svc.proposeMemory(ProposeMemoryRequest(Some(FredId), kind, text, "chat", Some(confidence)))

  private def proposeAcceptProfileFact(svc: PersonService, text: String): IO[AgentError, MemoryItem] =
    svc.proposeMemory(ProposeMemoryRequest(Some(FredId), MemoryKind.Fact, text, "onboarding:profile", Some(0.9)))

  def spec: Spec[Any, AgentError] = suite("PersonServiceSpec")(

    // --- Seeded baseline ---

    test("seeded persons are available") {
      withService { svc =>
        svc.listPersons.map(persons =>
          assertTrue(
            persons.length == 2,
            persons.exists(_.id == PersonId("fred")),
            persons.exists(_.id == PersonId("paula"))
          )
        )
      }
    },
    test("seeded goal is available and open") {
      withService { svc =>
        svc.listGoals(None, None).map(goals =>
          assertTrue(goals.exists(g => g.id == SampleGoalId && g.status == GoalStatus.Open))
        )
      }
    },

    // --- Commitments ---

    test("propose commitment creates with proposed status") {
      withService { svc =>
        svc.proposeCommitment(ProposeCommitmentRequest(
          ownerPersonId = FredId,
          text = "Send Graham update by Friday",
          source = "email:gmail-msg-123",
          evidence = "Could you send me the update by Friday?",
          dueAt = None
        )).map(c =>
          assertTrue(
            c.status == CommitmentStatus.Proposed,
            c.ownerPersonId == FredId,
            c.id.value.nonEmpty
          )
        )
      }
    },
    test("list commitments filters by owner") {
      withService { svc =>
        for {
          _        <- svc.proposeCommitment(ProposeCommitmentRequest(FredId, "task1", "src", "ev", None))
          _        <- svc.proposeCommitment(ProposeCommitmentRequest(PaulaId, "task2", "src", "ev", None))
          fredOnly <- svc.listCommitments(Some(FredId), None)
        } yield assertTrue(
          fredOnly.length == 1,
          fredOnly.exists(_.text == "task1")
        )
      }
    },
    test("list commitments filters by status") {
      withService { svc =>
        for {
          _        <- svc.proposeCommitment(ProposeCommitmentRequest(FredId, "task1", "src", "ev", None))
          proposed <- svc.listCommitments(None, Some("proposed"))
          open     <- svc.listCommitments(None, Some("open"))
        } yield assertTrue(proposed.length == 1, open.isEmpty)
      }
    },
    test("re-proposing a commitment by external source updates in place (idempotent)") {
      withService { svc =>
        for {
          first  <- svc.proposeCommitment(ProposeCommitmentRequest(FredId, "Reply to Graham", "email:gmail-msg-1", "ev1", None))
          second <- svc.proposeCommitment(ProposeCommitmentRequest(FredId, "Reply to Graham today", "email:gmail-msg-1", "ev2", None))
          all    <- svc.listCommitments(Some(FredId), None)
        } yield assertTrue(
          first.id == second.id,
          second.text == "Reply to Graham today",
          all.count(_.source == "email:gmail-msg-1") == 1
        )
      }
    },
    test("generic 'chat' source is not deduped — each propose is a new commitment") {
      withService { svc =>
        for {
          _   <- svc.proposeCommitment(ProposeCommitmentRequest(FredId, "task A", "chat", "ev", None))
          _   <- svc.proposeCommitment(ProposeCommitmentRequest(FredId, "task B", "chat", "ev", None))
          all <- svc.listCommitments(Some(FredId), None)
        } yield assertTrue(all.count(_.source == "chat") == 2)
      }
    },

    // --- Memory lifecycle ---

    test("propose memory is gateless — created accepted") {
      withService { svc =>
        svc.proposeMemory(ProposeMemoryRequest(
          personId = Some(FredId),
          kind = MemoryKind.Preference,
          text = "Prefer draft-only email actions",
          source = "chat:local",
          confidence = Some(0.9)
        )).map(m =>
          assertTrue(
            m.status == MemoryStatus.Accepted,
            m.kind == MemoryKind.Preference,
            m.text == "Prefer draft-only email actions"
          )
        )
      }
    },
    test("a written fact is immediately accepted (gateless)") {
      withService { svc =>
        svc.proposeMemory(ProposeMemoryRequest(Some(FredId), MemoryKind.Preference, "Likes morning meetings", "chat", Some(0.7)))
          .map(m => assertTrue(m.status == MemoryStatus.Accepted))
      }
    },
    test("reject memory records reason") {
      withService { svc =>
        for {
          m <- svc.proposeMemory(ProposeMemoryRequest(Some(FredId), MemoryKind.Fact, "Wrong fact", "chat", None))
          r <- svc.rejectMemory(m.id, Some("user clarified"))
        } yield assertTrue(r.status == MemoryStatus.Rejected)
      }
    },
    test("supersede sets superseded_by on the old item") {
      withService { svc =>
        for {
          oldM     <- proposeAndAcceptMemory(svc, "Fred works at Acme")
          newM     <- proposeAndAcceptMemory(svc, "Fred works at Beta Corp")
          _        <- svc.supersedeMemory(newM.id, oldM.id)
          afterAll <- svc.listMemory(Some(FredId), None, None)
        } yield assertTrue(
          afterAll.find(_.id == oldM.id).flatMap(_.supersededById).contains(newM.id)
        )
      }
    },

    // --- Approvals ---

    test("request approval creates with requested status") {
      withService { svc =>
        svc.requestApproval(RequestApprovalRequest(
          requestedBy = "agent",
          requiredPersonId = Some(FredId),
          actionType = "calendar.propose_event",
          payloadJson = """{"summary":"family dinner"}"""
        )).map(a =>
          assertTrue(
            a.status == ApprovalStatus.Requested,
            a.actionType == "calendar.propose_event",
            a.decidedAt.isEmpty
          )
        )
      }
    },
    test("list approvals filters by status") {
      withService { svc =>
        for {
          _            <- svc.requestApproval(RequestApprovalRequest("agent", None, "test", "{}"))
          _            <- svc.requestApproval(RequestApprovalRequest("agent", None, "test2", "{}"))
          requested    <- svc.listApprovals(Some("requested"))
          approved     <- svc.listApprovals(Some("approved"))
        } yield assertTrue(requested.length == 2, approved.isEmpty)
      }
    },

    // --- Goals ---

    test("proposed goal starts open") {
      withService { svc =>
        svc.proposeGoal(ProposeGoalRequest(
          ownerPersonId = FredId,
          title = "Ship the migration",
          outcome = "Migration deployed to prod with rollback plan documented.",
          evidenceRule = "Prod deploy SHA + rollback runbook link",
          constraintsJson = None,
          source = Some("test")
        )).map(g =>
          assertTrue(g.status == GoalStatus.Open, g.title == "Ship the migration", g.id.value.nonEmpty)
        )
      }
    },
    test("re-proposing a goal by external source updates mutable fields, keeps outcome immutable") {
      withService { svc =>
        for {
          first  <- svc.proposeGoal(ProposeGoalRequest(FredId, "Plan trip", "Trip booked", "Booking confirmation", None, Some("email:gmail-msg-7")))
          second <- svc.proposeGoal(ProposeGoalRequest(FredId, "Plan summer trip", "DIFFERENT outcome", "DIFFERENT rule", None, Some("email:gmail-msg-7")))
          all    <- svc.listGoals(Some(FredId), None)
        } yield assertTrue(
          first.id == second.id,
          second.title == "Plan summer trip",
          second.outcome == "Trip booked",
          second.evidenceRule == "Booking confirmation",
          all.count(_.source.contains("email:gmail-msg-7")) == 1
        )
      }
    },
    test("goal status transitions and audit records each step") {
      withService { svc =>
        for {
          g       <- svc.proposeGoal(ProposeGoalRequest(FredId, "task", "out", "ev", None, None))
          _       <- svc.updateGoalStatus(g.id, UpdateGoalStatusRequest(GoalStatus.Blocked, Some("waiting on stakeholder")))
          blocked <- svc.getGoal(g.id)
          _       <- svc.updateGoalStatus(g.id, UpdateGoalStatusRequest(GoalStatus.Done, None))
          done    <- svc.getGoal(g.id)
        } yield assertTrue(
          blocked.exists(_.goal.status == GoalStatus.Blocked),
          blocked.exists(_.goal.blockedReason.contains("waiting on stakeholder")),
          done.exists(_.goal.status == GoalStatus.Done),
          done.exists(_.goal.outcome == "out"),
          done.exists(_.goal.evidenceRule == "ev")
        )
      }
    },
    test("appending evidence preserves goal identity") {
      withService { svc =>
        for {
          g   <- svc.proposeGoal(ProposeGoalRequest(FredId, "task", "out", "ev", None, None))
          _   <- svc.appendGoalEvidence(g.id, AppendGoalEvidenceRequest("file", "/workspace/evidence/commit.txt", Some("merged")))
          _   <- svc.appendGoalEvidence(g.id, AppendGoalEvidenceRequest("commitment", "C123", None))
          gwe <- svc.getGoal(g.id)
        } yield assertTrue(
          gwe.exists(_.evidence.length == 2),
          gwe.exists(_.evidence.exists(_.kind == "file")),
          gwe.exists(_.evidence.exists(_.kind == "commitment")),
          gwe.exists(_.goal.id == g.id)
        )
      }
    },
    test("listing goals filters by status") {
      withService { svc =>
        for {
          a         <- svc.proposeGoal(ProposeGoalRequest(FredId, "open one", "o", "e", None, None))
          b         <- svc.proposeGoal(ProposeGoalRequest(FredId, "done one", "o", "e", None, None))
          _         <- svc.updateGoalStatus(b.id, UpdateGoalStatusRequest(GoalStatus.Done, None))
          openGoals <- svc.listGoals(Some(FredId), Some("open"))
          doneGoals <- svc.listGoals(Some(FredId), Some("done"))
        } yield assertTrue(
          openGoals.exists(_.id == a.id),
          !openGoals.exists(_.id == b.id),
          doneGoals.exists(_.id == b.id)
        )
      }
    },

    // --- Recall (global, scopeless) ---

    test("search returns accepted hits, excludes proposed and rejected") {
      withService { svc =>
        for {
          a    <- proposeAndAcceptMemory(svc, "Fred prefers morning standups", MemoryKind.Preference, confidence = 0.8)
          b    <- svc.proposeMemory(ProposeMemoryRequest(Some(FredId), MemoryKind.Preference, "Likes afternoon walks", "chat", Some(0.8)))
          hits <- svc.searchMemory("morning", None, None, None, 10)
        } yield assertTrue(
          hits.exists(_.item.id == a.id),
          !hits.exists(_.item.id == b.id)
        )
      }
    },
    test("contextBundle returns ranked accepted facts plus recent observation events") {
      withService { svc =>
        for {
          a      <- proposeAndAcceptMemory(svc, "Likes morning standups", MemoryKind.Preference, confidence = 0.9)
          b      <- proposeAndAcceptMemory(svc, "Works on the migration project", MemoryKind.Fact, confidence = 0.7)
          _      <- svc.logEvent(LogEventRequest("agent", "obs.standup.empty", "observation", None, None, Some("Standup cancelled"), None))
          bundle <- svc.contextBundle(Some(FredId), 5, 5)
        } yield assertTrue(
          bundle.facts.exists(_.item.id == a.id),
          bundle.facts.exists(_.item.id == b.id),
          bundle.events.exists(_.action == "obs.standup.empty"),
          bundle.facts.headOption.exists(h => bundle.facts.lastOption.forall(_.score <= h.score))
        )
      }
    },
    test("findConflicts returns same-kind accepted matches") {
      withService { svc =>
        for {
          a         <- proposeAndAcceptMemory(svc, "Likes morning meetings", MemoryKind.Preference, confidence = 0.8)
          conflicts <- svc.findConflicts(Some(FredId), "preference", "morning meetings")
        } yield assertTrue(conflicts.exists(_.id == a.id))
      }
    },
    test("consolidate is idempotent and links memory to origin event") {
      withService { svc =>
        for {
          e1     <- svc.logEvent(LogEventRequest("agent", "note.preference", "session_note", None, None, Some("Fred said he likes morning sync"), None))
          e2     <- svc.logEvent(LogEventRequest("agent", "obs.calendar", "observation", None, None, Some("Calendar empty on Friday"), None))
          first  <- svc.consolidateMemory(None)
          second <- svc.consolidateMemory(None)
        } yield assertTrue(
          first.size == 2,
          first.forall(_.status == MemoryStatus.Accepted),
          first.forall(_.originEventId.isDefined),
          first.flatMap(_.originEventId).toSet == Set(e1.id, e2.id),
          second.isEmpty
        )
      }
    },
    test("event log + searchEvents round-trip") {
      withService { svc =>
        for {
          _         <- svc.logEvent(LogEventRequest("user", "utterance.move", "utterance", None, None, Some("Move that meeting to next Tuesday"), None))
          _         <- svc.logEvent(LogEventRequest("agent", "decision.skip", "decision", None, None, Some("Skipped re-encoding the Q3 video"), None))
          all       <- svc.listEvents(None, None, None, 50)
          decisions <- svc.listEvents(Some("decision"), None, None, 50)
          hits      <- svc.searchEvents("meeting", None, None, 10)
        } yield assertTrue(
          all.exists(_.action == "utterance.move"),
          all.exists(_.action == "decision.skip"),
          decisions.size == 1,
          decisions.exists(_.action == "decision.skip"),
          hits.exists(_.event.action == "utterance.move")
        )
      }
    },
    test("as-of recall includes superseded item before supersession, excludes after") {
      withService { svc =>
        for {
          first      <- proposeAndAcceptMemory(svc, "Fred works at Acme Corp", confidence = 0.8)
          _          <- TestClock.adjust(Duration.fromSeconds(1))
          second     <- proposeAndAcceptMemory(svc, "Fred works at Beta Corp", confidence = 0.9)
          _          <- svc.supersedeMemory(second.id, first.id)
          asOf        = second.createdAt.minusMillis(1)
          hitsBefore <- svc.searchMemory("Acme Beta", None, None, Some(asOf), 10)
          hitsAfter  <- svc.searchMemory("Acme Beta", None, None, None, 10)
        } yield assertTrue(
          hitsBefore.exists(_.item.id == first.id),
          !hitsAfter.exists(_.item.id == first.id),
          hitsAfter.exists(_.item.id == second.id)
        )
      }
    },

    // --- Pinned profile recall (non-decaying) ---

    test("profileFacts returns only onboarding-sourced accepted facts") {
      withService { svc =>
        for {
          p     <- proposeAcceptProfileFact(svc, "Fred lives in London")
          _     <- proposeAndAcceptMemory(svc, "Likes morning standups")
          facts <- svc.profileFacts(50)
        } yield assertTrue(
          facts.exists(_.id == p.id),
          facts.forall(_.source.startsWith("onboarding"))
        )
      }
    },
    test("profileFacts excludes superseded profile facts") {
      withService { svc =>
        for {
          oldP  <- proposeAcceptProfileFact(svc, "Fred works at Acme")
          _     <- TestClock.adjust(Duration.fromSeconds(1))
          newP  <- proposeAcceptProfileFact(svc, "Fred works at Beta Corp")
          _     <- svc.supersedeMemory(newP.id, oldP.id)
          facts <- svc.profileFacts(50)
        } yield assertTrue(
          !facts.exists(_.id == oldP.id),
          facts.exists(_.id == newP.id)
        )
      }
    },

    // --- New edge cases (hardening) ---

    test("reject on nonexistent memory id fails with NotFound") {
      withService { svc =>
        svc.rejectMemory(MemoryId("nope"), None).either.map(e =>
          assertTrue(e.swap.toOption.collect { case AgentError.NotFound(t, id) => (t, id) }.contains(("memory_item", "nope")))
        )
      }
    },
    test("supersede on nonexistent memory ids fails with NotFound") {
      withService { svc =>
        svc.supersedeMemory(MemoryId("new-missing"), MemoryId("old-missing")).either.map(e =>
          assertTrue(e.swap.toOption.exists(_.isInstanceOf[AgentError.NotFound]))
        )
      }
    },
    test("findConflicts with no matches returns empty list") {
      withService { svc =>
        svc.findConflicts(None, "preference", "nothing here at all").map(xs =>
          assertTrue(xs.isEmpty)
        )
      }
    },
    test("contextBundle with no facts and no events returns empty bundle") {
      withService { svc =>
        svc.contextBundle(Some(PersonId("ghost")), 10, 10).map(b =>
          assertTrue(b.facts.isEmpty, b.events.isEmpty)
        )
      }
    },
    test("consolidate with no events returns empty list (no audit row)") {
      withService { svc =>
        for {
          before <- svc.listEvents(Some("state"), None, None, 50).map(_.size)
          out    <- svc.consolidateMemory(None)
          after  <- svc.listEvents(Some("state"), None, None, 50).map(_.size)
        } yield assertTrue(out.isEmpty, before == after)
      }
    },
    test("supersession chain A → B → C tracks each old item's direct superseder") {
      withService { svc =>
        for {
          a <- proposeAndAcceptMemory(svc, "Fact A")
          b <- proposeAndAcceptMemory(svc, "Fact B")
          c <- proposeAndAcceptMemory(svc, "Fact C")
          _ <- svc.supersedeMemory(b.id, a.id)
          _ <- svc.supersedeMemory(c.id, b.id)
          all <- svc.listMemory(Some(FredId), None, None)
        } yield assertTrue(
          all.find(_.id == a.id).flatMap(_.supersededById).contains(b.id),
          all.find(_.id == b.id).flatMap(_.supersededById).contains(c.id),
          all.find(_.id == c.id).flatMap(_.supersededById).isEmpty
        )
      }
    },
    test("as-of respects valid_from / valid_until boundary") {
      withService { svc =>
        val t0 = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val t1 = java.time.Instant.parse("2026-06-01T00:00:00Z")
        for {
          m <- svc.proposeMemory(ProposeMemoryRequest(
                 Some(FredId), MemoryKind.Fact, "Worked at Acme 2026",
                 "chat", Some(0.8), validFrom = Some(t0), validUntil = Some(t1)
               ))
          inside <- svc.searchMemory("Acme", None, None,
                                     Some(java.time.Instant.parse("2026-03-15T00:00:00Z")), 10)
          after  <- svc.searchMemory("Acme", None, None,
                                     Some(java.time.Instant.parse("2026-07-01T00:00:00Z")), 10)
          before <- svc.searchMemory("Acme", None, None,
                                     Some(java.time.Instant.parse("2025-12-01T00:00:00Z")), 10)
        } yield assertTrue(
          inside.exists(_.item.id == m.id),
          !after.exists(_.item.id == m.id),
          !before.exists(_.item.id == m.id)
        )
      }
    },
    test("rerank is deterministic when items share createdAt") {
      withService { svc =>
        for {
          a    <- proposeAndAcceptMemory(svc, "morning standups", MemoryKind.Preference, confidence = 0.5)
          b    <- proposeAndAcceptMemory(svc, "morning routines",  MemoryKind.Preference, confidence = 0.5)
          h1   <- svc.searchMemory("morning", None, None, None, 10)
          h2   <- svc.searchMemory("morning", None, None, None, 10)
        } yield assertTrue(
          h1.map(_.item.id) == h2.map(_.item.id),
          h1.exists(_.item.id == a.id),
          h1.exists(_.item.id == b.id)
        )
      }
    }
  )
}
