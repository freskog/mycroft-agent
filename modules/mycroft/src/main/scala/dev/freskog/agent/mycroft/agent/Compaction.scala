package dev.freskog.agent.mycroft.agent

import dev.freskog.agent.common.Message

/** Rolling-window resolver. Operates only on persisted user/assistant messages;
 *  the system prompt + context bundle are assembled outside the window, so the
 *  budget passed here is already net of their token cost. */
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
}
