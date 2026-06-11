package dev.freskog.agent.mycroft.api

import dev.freskog.agent.mycroft.domain.AgentEvent

import zio.Chunk
import zio.json._
import zio.json.ast.Json

/** Serialises a typed AgentEvent into an OpenAI-style SSE text frame:
 *  `event: <name>\ndata: <json>\n\n`. */
object Sse {

  def frame(e: AgentEvent): String =
    s"event: ${AgentEvent.name(e)}\ndata: ${payload(e).toJson}\n\n"

  private def payload(e: AgentEvent): Json = {
    val base = List[(String, Json)]("channel" -> Json.Str(e.channel), "message_id" -> Json.Str(e.messageId))
    val extra: List[(String, Json)] = e match {
      case s: AgentEvent.Started =>
        List(
          "in_reply_to" -> s.inReplyTo.map(Json.Str(_)).getOrElse(Json.Null),
          "model"       -> Json.Str(s.model),
          "started_at"  -> Json.Str(s.startedAt.toString)
        )
      case r: AgentEvent.Reasoning  => List("delta" -> Json.Str(r.delta))
      case c: AgentEvent.Content    => List("delta" -> Json.Str(c.delta))
      case t: AgentEvent.ToolCall   => List("tool" -> Json.Str(t.tool), "args" -> Json.Str(t.args))
      case t: AgentEvent.ToolResult => List("tool" -> Json.Str(t.tool), "ok" -> Json.Bool(t.ok), "summary" -> Json.Str(t.summary))
      case d: AgentEvent.Done       => List(
          "stop_reason" -> Json.Str(d.stopReason),
          "tokens_in"   -> Json.Num(d.tokensIn),
          "tokens_out"  -> Json.Num(d.tokensOut),
          "elapsed_ms"  -> Json.Num(d.elapsedMs),
          "ttft_ms"     -> d.ttftMs.map(v => Json.Num(v)).getOrElse(Json.Null),
          "gen_tps"     -> d.genTps.map(v => Json.Num(v)).getOrElse(Json.Null),
          "pp_tps"      -> d.ppTps.map(v => Json.Num(v)).getOrElse(Json.Null),
          "model_calls"     -> Json.Num(d.modelCalls),
          "turn_gen_tokens" -> Json.Num(d.turnGenTokens),
          "turn_gen_ms"     -> Json.Num(d.turnGenMs)
        )
      case e: AgentEvent.Error      => List("kind" -> Json.Str(e.kind), "message" -> Json.Str(e.message))
    }
    Json.Obj(Chunk.fromIterable(base ++ extra))
  }
}
