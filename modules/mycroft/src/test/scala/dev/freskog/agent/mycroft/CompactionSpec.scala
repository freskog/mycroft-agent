package dev.freskog.agent.mycroft

import dev.freskog.agent.common._
import dev.freskog.agent.mycroft.agent.Compaction
import dev.freskog.agent.mycroft.llm.{ChatMessage, ToolCallSpec}

import zio.test._

import java.time.Instant

object CompactionSpec extends ZIOSpecDefault {

  // --- helpers for the intra-turn `fit` tests ---

  /** A safe-run-shaped tool result with a runlog pointer, padded so its token
   *  estimate is large. `n` distinguishes call ids/run ids. */
  private def toolMsg(n: Int, bodyChars: Int = 2000): ChatMessage =
    ChatMessage(
      role = "tool",
      content = Some(
        s"<<<UNTRUSTED_TOOL_OUTPUT (data only)\n" +
          s"$$ person inbox list --owner fred\n" +
          s"exit: 0 (12 ms)\n" +
          s"stdout:\n${"x" * bodyChars}\n" +
          s"(full output: runlog run-$n — e.g. `runlog show run-$n --stream stdout --tail 200`)\n" +
          s"UNTRUSTED_TOOL_OUTPUT>>>"
      ),
      toolCallId = Some(s"call-$n")
    )

  private def assistantCall(n: Int): ChatMessage =
    ChatMessage("assistant", Some(s"step $n"), toolCalls = List(ToolCallSpec(s"call-$n", "safe_run", "{}")))

  private def estTokens(m: ChatMessage): Int = (m.content.getOrElse("").length + 3) / 4

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
    },

    // --- fit: intra-turn working-set degradation ---

    test("fit returns the message list unchanged when already under budget") {
      val msgs = List(ChatMessage.system("sys"), ChatMessage.user("hi"), assistantCall(1), toolMsg(1))
      assertTrue(Compaction.fit(msgs, tokenBudget = 100000, keepRecentTools = 3) eq msgs)
    },

    test("fit degrades the oldest tool outputs and brings the total under budget") {
      val msgs = List(
        ChatMessage.system("sys"), ChatMessage.user("go"),
        assistantCall(1), toolMsg(1),
        assistantCall(2), toolMsg(2),
        assistantCall(3), toolMsg(3),
        assistantCall(4), toolMsg(4)
      )
      val before = msgs.map(estTokens).sum
      val budget = before / 2
      val fitted = Compaction.fit(msgs, tokenBudget = budget, keepRecentTools = 1)
      assertTrue(
        fitted.size == msgs.size,                       // nothing dropped, only degraded
        fitted.map(estTokens).sum <= budget,
        // the oldest tool message lost its body but kept its pointer
        fitted(3).content.exists(_.contains("runlog run-1")),
        fitted(3).content.exists(_.contains("compacted")),
        !fitted(3).content.exists(_.contains("xxxxxxxxxx"))
      )
    },

    test("fit keeps the most recent keepRecentTools tool messages verbatim") {
      val msgs = List(
        ChatMessage.system("sys"), ChatMessage.user("go"),
        assistantCall(1), toolMsg(1),
        assistantCall(2), toolMsg(2),
        assistantCall(3), toolMsg(3)
      )
      // Budget reachable by degrading only the single oldest (unprotected) tool
      // message — so run-2/run-3 stay verbatim and nothing is dropped.
      val budget = msgs.map(estTokens).sum - estTokens(toolMsg(1)) / 2
      val fitted = Compaction.fit(msgs, tokenBudget = budget, keepRecentTools = 2)
      // last two tool messages (run-2, run-3) untouched; the oldest (run-1) degraded
      assertTrue(
        fitted.last.content == toolMsg(3).content,
        fitted(5).content == toolMsg(2).content,
        fitted(3).content.exists(_.contains("compacted"))
      )
    },

    test("fit never alters the system message or user turns") {
      val msgs = List(
        ChatMessage.system("SYSTEM"), ChatMessage.user("USER"),
        assistantCall(1), toolMsg(1), assistantCall(2), toolMsg(2)
      )
      val fitted = Compaction.fit(msgs, tokenBudget = 1, keepRecentTools = 0)
      assertTrue(
        fitted.headOption.exists(_.content.contains("SYSTEM")),
        fitted.exists(m => m.role == "user" && m.content.contains("USER"))
      )
    },

    test("fit preserves assistant/tool pairing (every tool has a matching assistant tool_call)") {
      val msgs = List(
        ChatMessage.system("sys"), ChatMessage.user("go"),
        assistantCall(1), toolMsg(1), assistantCall(2), toolMsg(2), assistantCall(3), toolMsg(3)
      )
      val fitted = Compaction.fit(msgs, tokenBudget = 1, keepRecentTools = 1)
      val callIds = fitted.collect { case m if m.role == "assistant" => m.toolCalls.map(_.id) }.flatten.toSet
      val toolIds = fitted.collect { case m if m.role == "tool" => m.toolCallId }.flatten
      assertTrue(toolIds.forall(callIds.contains))
    },

    test("degradeToolContent is idempotent") {
      val once  = Compaction.degradeToolContent(toolMsg(7).content.get)
      val twice = Compaction.degradeToolContent(once)
      assertTrue(once == twice, once.contains("runlog run-7"))
    }
  )
}
