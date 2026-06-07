package dev.freskog.agent.person

import dev.freskog.agent.common._
import dev.freskog.agent.person.persistence._
import dev.freskog.agent.person.seed.SeedData
import dev.freskog.agent.person.seed.SeedData.FredId

import zio._
import zio.test._

import java.time.Instant

object InboxRepoSpec extends ZIOSpecDefault {

  def spec = suite("InboxRepoSpec")(
    test("upsert deduplicates by provider and external_id") {
      ZIO.scoped {
        for {
          db   <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
          _    <- Migrations.migrate(db)
          _    <- SeedData.seed(db)
          repo  = Repos.sqliteInboxMessageRepo(db)
          now   = Instant.parse("2026-06-01T10:00:00Z")
          msg   = InboxMessage(
                    id = InboxMessageId("inbox-1"),
                    provider = "gmail",
                    externalId = "gmail-msg-abc",
                    threadId = Some("thread-1"),
                    fromAddr = "boss@work.com",
                    subject = "Please review",
                    bodyText = "Can you review by Friday?",
                    receivedAt = now,
                    ownerPersonId = FredId,
                    triageStatus = TriageStatus.Pending,
                    triagedAt = None,
                    sourceEventId = None
                  )
          _    <- repo.upsert(msg)
          _    <- repo.upsert(msg.copy(id = InboxMessageId("inbox-2")))
          all  <- repo.findAll(Some(FredId), Some("pending"), 10)
          cnt  <- repo.countPending(FredId)
        } yield assertTrue(all.size == 1, cnt == 1)
      }
    },

    test("updateStatus marks message triaged") {
      ZIO.scoped {
        for {
          db   <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
          _    <- Migrations.migrate(db)
          _    <- SeedData.seed(db)
          repo  = Repos.sqliteInboxMessageRepo(db)
          now   = Instant.parse("2026-06-01T10:00:00Z")
          id    = InboxMessageId("inbox-1")
          msg   = InboxMessage(
                    id = id,
                    provider = "gmail",
                    externalId = "gmail-msg-xyz",
                    threadId = None,
                    fromAddr = "a@b.com",
                    subject = "Hi",
                    bodyText = "Hello",
                    receivedAt = now,
                    ownerPersonId = FredId,
                    triageStatus = TriageStatus.Pending,
                    triagedAt = None,
                    sourceEventId = None
                  )
          _      <- repo.upsert(msg)
          later  <- Clock.instant
          _      <- repo.updateStatus(id, TriageStatus.Triaged, later, None)
          found  <- repo.findById(id)
        } yield assertTrue(
          found.exists(_.triageStatus == TriageStatus.Triaged),
          found.flatMap(_.triagedAt).isDefined
        )
      }
    },

    test("findAll returns oldest pending first when requested") {
      ZIO.scoped {
        for {
          db   <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
          _    <- Migrations.migrate(db)
          _    <- SeedData.seed(db)
          repo  = Repos.sqliteInboxMessageRepo(db)
          older = InboxMessage(
                    id = InboxMessageId("inbox-old"),
                    provider = "gmail",
                    externalId = "gmail-msg-old",
                    threadId = None,
                    fromAddr = "old@b.com",
                    subject = "Old",
                    bodyText = "Old mail",
                    receivedAt = Instant.parse("2026-05-01T10:00:00Z"),
                    ownerPersonId = FredId,
                    triageStatus = TriageStatus.Pending,
                    triagedAt = None,
                    sourceEventId = None
                  )
          newer = older.copy(
                    id = InboxMessageId("inbox-new"),
                    externalId = "gmail-msg-new",
                    subject = "New",
                    receivedAt = Instant.parse("2026-06-01T10:00:00Z")
                  )
          _     <- repo.upsert(older)
          _     <- repo.upsert(newer)
          asc   <- repo.findAll(Some(FredId), Some("pending"), 10, oldestFirst = true)
          desc  <- repo.findAll(Some(FredId), Some("pending"), 10, oldestFirst = false)
        } yield assertTrue(
          asc.headOption.map(_.id.value).contains("inbox-old"),
          desc.headOption.map(_.id.value).contains("inbox-new")
        )
      }
    }
  )
}
