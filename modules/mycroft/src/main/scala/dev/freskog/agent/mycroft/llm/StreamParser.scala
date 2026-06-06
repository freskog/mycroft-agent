package dev.freskog.agent.mycroft.llm

/** Turns raw OpenAI SSE lines into ChatChunks. The OpenAI streaming format is
 *  newline-delimited `data: <json>` frames terminated by `data: [DONE]`; blank
 *  lines and non-`data:` lines are ignored. */
object StreamParser {

  def parseLine(line: String): Option[ChatChunk] = {
    val trimmed = line.trim
    if (trimmed.isEmpty) None
    else if (trimmed.startsWith("data:")) ChatProtocol.parseChunk(trimmed.drop("data:".length).trim)
    else None
  }
}
