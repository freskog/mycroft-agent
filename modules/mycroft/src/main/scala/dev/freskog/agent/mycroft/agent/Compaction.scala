package dev.freskog.agent.mycroft.agent

import dev.freskog.agent.common.Message
import dev.freskog.agent.mycroft.llm.ChatMessage

/** Two context-budget resolvers:
 *
 *  - `window` is the CROSS-TURN rolling window over persisted user/assistant
 *    rows, applied once at turn start (the system prompt + context bundle are
 *    assembled outside it).
 *  - `fit` is the INTRA-TURN working-set fitter applied before every model call
 *    inside the agentic loop. As the loop appends assistant + tool messages it
 *    would otherwise grow unbounded (up to `maxToolIterations` × 4 KB previews)
 *    and overflow the model mid-turn. `fit` keeps the working set under a token
 *    budget by progressively degrading the OLDEST tool outputs to just their
 *    `runlog` pointer — lossless w.r.t. evidence, since the model can re-read the
 *    full output via `runlog <id>`. */
object Compaction {

  /** Given channel messages in reverse-chronological order (newest first),
   *  return the chronological window that fits within `maxMessages` and
   *  `tokenBudget`. Only `user`/`assistant` rows should be passed in. Stops at
   *  the first message that would overflow the budget (keeps the most recent
   *  contiguous run). */
  def window(newestFirst: List[Message], maxMessages: Int, tokenBudget: Int): List[Message] = {
    @scala.annotation.tailrec
    def loop(remaining: List[Message], used: Int, acc: List[Message]): List[Message] =
      remaining match {
        case msg :: rest =>
          val cost = Prompt.estimateTokens(msg.content)
          if (acc.nonEmpty && used + cost > tokenBudget) acc
          else loop(rest, used + cost, msg :: acc)
        case Nil => acc
      }
    // Building by prepending while walking newest→oldest yields chronological order.
    loop(newestFirst.take(maxMessages), 0, Nil)
  }

  /** Marker appended to a degraded tool message; also the idempotence guard. */
  private val CompactedSuffix = "…(compacted — full output via runlog)"

  /** Approx token cost of a message: its content plus any tool-call arguments. */
  private def tokensOf(m: ChatMessage): Int =
    Prompt.estimateTokens(m.content.getOrElse("")) +
      m.toolCalls.map(tc => Prompt.estimateTokens(tc.arguments) + 4).sum

  private def total(ms: List[ChatMessage]): Int = ms.map(tokensOf).sum

  /** Fit the in-flight message list under `tokenBudget`. Returns it unchanged if
   *  already within budget. Otherwise degrades the content of older `tool`
   *  messages (keeping the most recent `keepRecentTools` intact), preserving
   *  every message's position and `toolCallId` so the assistant→tool pairing the
   *  OpenAI API requires stays valid. As a last resort — only if degrading all
   *  eligible tool outputs is still not enough — it drops the oldest
   *  assistant+tool step groups, never the leading system message or any `user`
   *  message. */
  def fit(msgs: List[ChatMessage], tokenBudget: Int, keepRecentTools: Int): List[ChatMessage] = {
    if (total(msgs) <= tokenBudget) msgs
    else {
      val protectedIdx =
        msgs.zipWithIndex.collect { case (m, i) if m.role == "tool" => i }.takeRight(keepRecentTools).toSet
      val degraded = msgs.zipWithIndex.map {
        case (m, i) if m.role == "tool" && !protectedIdx.contains(i) =>
          m.copy(content = m.content.map(degradeToolContent))
        case (m, _) => m
      }
      if (total(degraded) <= tokenBudget) degraded
      else dropOldestGroups(degraded, tokenBudget, keepRecentTools)
    }
  }

  /** Collapse one tool result to a compact observation plus its retrievable
   *  pointer. Idempotent. For a `safe-run` preview keep the command, exit and
   *  `runlog` pointer lines and drop the body; for a `run_skill` contract keep
   *  the status/summary head; otherwise keep the head and any pointer line. The
   *  untrusted-data fence (if present) is preserved so the content stays marked. */
  def degradeToolContent(content: String): String = {
    if (content.contains(CompactedSuffix)) content
    else {
      val lines      = content.linesIterator.toList
      val hasOpen    = lines.headOption.exists(_.startsWith("<<<UNTRUSTED_TOOL_OUTPUT"))
      val hasClose   = lines.lastOption.exists(_.contains("UNTRUSTED_TOOL_OUTPUT>>>"))
      val open       = if (hasOpen) lines.take(1) else Nil
      val close      = if (hasClose) lines.takeRight(1) else Nil
      val body       = lines.drop(open.size).dropRight(close.size)
      val kept =
        body.filter(l => l.startsWith("$ ") || l.startsWith("exit:") || l.contains("(full output: runlog "))
      val core =
        if (kept.nonEmpty) kept
        else List(body.find(_.trim.nonEmpty).getOrElse("").take(200))
      (open ++ core ++ List(CompactedSuffix) ++ close).mkString("\n")
    }
  }

  /** Last-resort: drop the oldest assistant-with-tool-calls step and its tool
   *  replies, repeating until under budget. Never drops the leading system
   *  message, any `user` message, or the most recent `keepRecentTools` tool
   *  groups. */
  private def dropOldestGroups(msgs: List[ChatMessage], tokenBudget: Int, keepRecentTools: Int): List[ChatMessage] = {
    @scala.annotation.tailrec
    def loop(cur: List[ChatMessage]): List[ChatMessage] =
      if (total(cur) <= tokenBudget) cur
      else {
        val protectedIds =
          cur.collect { case m if m.role == "tool" => m.toolCallId }.flatten.takeRight(keepRecentTools).toSet
        // The oldest assistant step whose tool replies are all unprotected.
        val idx = cur.indexWhere(m =>
          m.role == "assistant" && m.toolCalls.nonEmpty && !m.toolCalls.exists(tc => protectedIds.contains(tc.id))
        )
        if (idx < 0) cur
        else {
          val ids   = cur(idx).toolCalls.map(_.id).toSet
          val pruned = cur.zipWithIndex.filterNot { case (m, i) =>
            i == idx || (m.role == "tool" && m.toolCallId.exists(ids.contains))
          }.map(_._1)
          if (pruned.size == cur.size) cur else loop(pruned)
        }
      }
    loop(msgs)
  }
}
