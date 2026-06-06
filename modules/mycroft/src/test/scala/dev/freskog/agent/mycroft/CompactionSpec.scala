package dev.freskog.agent.mycroft

import dev.freskog.agent.common._
import dev.freskog.agent.mycroft.agent.Compaction

import zio.test._

import java.time.Instant

object CompactionSpec extends ZIOSpecDefault {

  private def msg(id: String, content: String, secondsAgo: Long): Message =
    Message(
      id = MessageId(id),
      channelId = ChannelId("c"),
      role = MessageRole.User,
      personIdFrom = Some(PersonId("fred")),
      content = content,
      toolCallsJson = None,
      externalId = None,
      createdAt = Instant.parse("2026-01-01T00:00:00Z").minusSeconds(secondsAgo)
    )

  // newest first (as the repo returns)
  private val m3 = msg("m3", "newest", 0)
  private val m2 = msg("m2", "middle", 10)
  private val m1 = msg("m1", "oldest", 20)
  private val newestFirst = List(m3, m2, m1)

  def spec = suite("Compaction")(

    test("returns chronological order (oldest first)") {
      val w = Compaction.window(newestFirst, maxMessages = 20, tokenBudget = 10000)
      assertTrue(w.map(_.id.value) == List("m1", "m2", "m3"))
    },

    test("respects the message count cap, keeping the most recent") {
      val w = Compaction.window(newestFirst, maxMessages = 2, tokenBudget = 10000)
      assertTrue(w.map(_.id.value) == List("m2", "m3"))
    },

    test("stops at the budget keeping the most recent contiguous run") {
      // each content ~ a few tokens; budget that fits ~one message
      val w = Compaction.window(newestFirst, maxMessages = 20, tokenBudget = 2)
      assertTrue(w.map(_.id.value) == List("m3"))
    },

    test("always keeps at least the newest message even if it exceeds budget") {
      val big = List(msg("big", "x" * 1000, 0))
      val w = Compaction.window(big, maxMessages = 20, tokenBudget = 1)
      assertTrue(w.map(_.id.value) == List("big"))
    }
  )
}
