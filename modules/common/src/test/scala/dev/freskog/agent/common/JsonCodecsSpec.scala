package dev.freskog.agent.common

import zio.test._
import zio.json._

import java.time.Instant

object JsonCodecsSpec extends ZIOSpecDefault {

  import JsonCodecs._

  def spec = suite("JsonCodecsSpec")(
    suite("Shell")(
      test("round-trip all shells") {
        val shells: List[Shell] = List(Shell.Bash, Shell.Zsh, Shell.Sh)
        val results = shells.map { s =>
          val json = s.toJson
          val back = json.fromJson[Shell]
          back == Right(s)
        }
        assertTrue(results.forall(identity))
      },
      test("reject unknown shell") {
        assertTrue("\"fish\"".fromJson[Shell].isLeft)
      }
    ),
    suite("ExitStatus")(
      test("round-trip Exited") {
        val status: ExitStatus = ExitStatus.Exited(42)
        val json = status.toJson
        val back = json.fromJson[ExitStatus]
        assertTrue(back == Right(status))
      },
      test("round-trip Killed") {
        val status: ExitStatus = ExitStatus.Killed("SIGTERM")
        val json = status.toJson
        val back = json.fromJson[ExitStatus]
        assertTrue(back == Right(status))
      },
      test("round-trip TimedOut") {
        val status: ExitStatus = ExitStatus.TimedOut
        val json = status.toJson
        val back = json.fromJson[ExitStatus]
        assertTrue(back == Right(status))
      },
      test("round-trip Failed") {
        val status: ExitStatus = ExitStatus.Failed("could not start")
        val json = status.toJson
        val back = json.fromJson[ExitStatus]
        assertTrue(back == Right(status))
      }
    ),
    suite("RunMetadata")(
      test("round-trip") {
        val now = Instant.parse("2026-05-25T12:00:00Z")
        val meta = RunMetadata(
          runId = "abc-123",
          command = "echo hello",
          shell = "bash",
          cwd = "/tmp",
          startedAt = now,
          finishedAt = now.plusMillis(500),
          durationMs = 500,
          exitCode = Some(0),
          timedOut = false,
          termination = None,
          stdoutBytes = 6,
          stderrBytes = 0,
          stdoutHead = "hello\n",
          stdoutTail = "hello\n",
          stderrHead = "",
          stderrTail = "",
          stdoutLog = "/tmp/.agent/runs/abc-123.stdout",
          stderrLog = "/tmp/.agent/runs/abc-123.stderr",
          metadataLog = "/tmp/.agent/runs/abc-123.json"
        )
        val json = meta.toJson
        val back = json.fromJson[RunMetadata]
        assertTrue(back == Right(meta))
      }
    ),
    suite("ScopeKind")(
      test("round-trip all kinds") {
        val kinds: List[ScopeKind] = List(
          ScopeKind.Private, ScopeKind.Shared, ScopeKind.Work,
          ScopeKind.Household, ScopeKind.School, ScopeKind.Other
        )
        val results = kinds.map { k =>
          k.toJson.fromJson[ScopeKind] == Right(k)
        }
        assertTrue(results.forall(identity))
      }
    ),
    suite("CommitmentStatus")(
      test("round-trip all statuses") {
        val statuses: List[CommitmentStatus] = List(
          CommitmentStatus.Proposed, CommitmentStatus.Open,
          CommitmentStatus.Done, CommitmentStatus.Ignored,
          CommitmentStatus.Cancelled
        )
        val results = statuses.map { s =>
          s.toJson.fromJson[CommitmentStatus] == Right(s)
        }
        assertTrue(results.forall(identity))
      }
    ),
    suite("Person")(
      test("round-trip with optional locale") {
        val p = Person(PersonId("p1"), "Fred", "Europe/London", Some("en-GB"), active = true)
        val json = p.toJson
        val back = json.fromJson[Person]
        assertTrue(back == Right(p))
      },
      test("round-trip without locale") {
        val p = Person(PersonId("p2"), "Paula", "Europe/London", None, active = true)
        val json = p.toJson
        val back = json.fromJson[Person]
        assertTrue(back == Right(p))
      },
      test("Person.id encodes as bare string") {
        val p = Person(PersonId("p1"), "Fred", "Europe/London", None, active = true)
        assertTrue(p.toJson.contains("\"id\":\"p1\""))
      }
    ),
    suite("Commitment")(
      test("round-trip") {
        val now = Instant.parse("2026-05-25T12:00:00Z")
        val c = Commitment(
          id = CommitmentId("c1"),
          ownerPersonId = PersonId("p1"),
          scopeId = ScopeId("s1"),
          status = CommitmentStatus.Proposed,
          text = "Send update",
          source = "email:123",
          evidence = "Could you send the update?",
          dueAt = Some(now.plusSeconds(86400)),
          createdAt = now,
          updatedAt = now
        )
        val json = c.toJson
        val back = json.fromJson[Commitment]
        assertTrue(back == Right(c))
      }
    ),
    suite("MemoryItem")(
      test("round-trip") {
        val now = Instant.parse("2026-05-25T12:00:00Z")
        val m = MemoryItem(
          id = MemoryId("m1"),
          personId = Some(PersonId("p1")),
          scopeId = Some(ScopeId("s1")),
          status = MemoryStatus.Proposed,
          kind = MemoryKind.Preference,
          text = "Prefer draft-only email actions",
          source = "chat:local",
          confidence = Some(0.9),
          createdAt = now,
          updatedAt = now
        )
        val json = m.toJson
        val back = json.fromJson[MemoryItem]
        assertTrue(back == Right(m))
      }
    ),
    suite("Approval")(
      test("round-trip") {
        val now = Instant.parse("2026-05-25T12:00:00Z")
        val a = Approval(
          id = ApprovalId("a1"),
          requestedBy = "agent",
          requiredPersonId = Some(PersonId("p1")),
          scopeId = Some(ScopeId("s1")),
          actionType = "calendar.propose_event",
          payloadJson = """{"summary":"meeting"}""",
          status = ApprovalStatus.Requested,
          createdAt = now,
          decidedAt = None
        )
        val json = a.toJson
        val back = json.fromJson[Approval]
        assertTrue(back == Right(a))
      }
    ),
    suite("AuditEvent")(
      test("round-trip") {
        val now = Instant.parse("2026-05-25T12:00:00Z")
        val e = AuditEvent(
          id = EventId("e1"),
          actor = "agent",
          action = "commitment.propose",
          category = "state",
          targetType = "commitment",
          targetId = Some("c1"),
          scopeId = Some(ScopeId("s1")),
          text = None,
          payloadJson = "{}",
          createdAt = now
        )
        val json = e.toJson
        val back = json.fromJson[AuditEvent]
        assertTrue(back == Right(e))
      }
    ),
    suite("AgentError")(
      test("NotFound encodes structured shape") {
        val e: AgentError = AgentError.NotFound("memory_item", "m1")
        val s = e.toJson
        assertTrue(
          s.contains("\"type\":\"not_found\""),
          s.contains("\"targetType\":\"memory_item\""),
          s.contains("\"id\":\"m1\"")
        )
      },
      test("BadRequest encodes type+message") {
        val e: AgentError = AgentError.BadRequest("bad")
        assertTrue(e.toJson.contains("\"type\":\"bad_request\""), e.toJson.contains("\"message\":\"bad\""))
      }
    )
  )
}
