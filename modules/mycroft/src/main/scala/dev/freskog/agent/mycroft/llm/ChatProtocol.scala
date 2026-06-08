package dev.freskog.agent.mycroft.llm

import zio.Chunk
import zio.json.ast.Json
import zio.json._

/** One tool call the model emitted (or that we replay back to it). */
final case class ToolCallSpec(id: String, name: String, arguments: String)

/** A message in OpenAI chat shape. `toolCalls` is non-empty on assistant
 *  messages that requested tools; `toolCallId` is set on `tool` results. */
final case class ChatMessage(
  role: String,
  content: Option[String],
  toolCalls: List[ToolCallSpec] = Nil,
  toolCallId: Option[String] = None
)

object ChatMessage {
  def system(content: String): ChatMessage    = ChatMessage("system", Some(content))
  def user(content: String): ChatMessage       = ChatMessage("user", Some(content))
  def assistant(content: String): ChatMessage  = ChatMessage("assistant", Some(content))
}

/** A streamed token delta, demuxed by which OpenAI field it carried. */
final case class ToolCallDelta(index: Int, id: Option[String], name: Option[String], argsFragment: Option[String])

/** Token accounting from the final `usage` chunk (requires
 *  `stream_options.include_usage`). Absent on per-token deltas. */
final case class ChatUsage(promptTokens: Option[Int], completionTokens: Option[Int])

/** Server-reported throughput, when the backend includes a `timings`/`stats`
 *  block (llama.cpp / LM Studio). Preferred over client-side estimates because
 *  it isolates prefill vs generation on the server. */
final case class ChatTimings(promptPerSecond: Option[Double], predictedPerSecond: Option[Double])

final case class ChatChunk(
  contentDelta: Option[String],
  reasoningDelta: Option[String],
  toolCallDeltas: List[ToolCallDelta],
  finishReason: Option[String],
  usage: Option[ChatUsage] = None,
  timings: Option[ChatTimings] = None
)

/** Sampling knobs sent to LM Studio. Defaults follow Qwen3's recommended
 *  *thinking-mode* settings, plus a `presence_penalty` to break the reasoning
 *  repetition loops that otherwise burn the whole token budget without ever
 *  emitting an answer or a tool call. `top_k`/`min_p` are llama.cpp/MLX
 *  extensions that LM Studio honours and other servers ignore. */
final case class SamplingParams(
  temperature: Double = 0.6,
  topP: Double = 0.95,
  topK: Int = 20,
  minP: Double = 0.0,
  presencePenalty: Double = 1.0
)

object ChatProtocol {

  /** Build the `/v1/chat/completions` request body as a JSON string. */
  def encodeBody(
    model: String,
    messages: List[ChatMessage],
    maxTokens: Int,
    stream: Boolean,
    toolsJson: Option[String],
    sampling: SamplingParams = SamplingParams()
  ): String = {
    val msgArr = Json.Arr(Chunk.fromIterable(messages.map(encodeMessage)))
    val base = List(
      "model"            -> Json.Str(model),
      "messages"         -> msgArr,
      "stream"           -> Json.Bool(stream),
      "max_tokens"       -> Json.Num(maxTokens),
      "temperature"      -> Json.Num(sampling.temperature),
      "top_p"            -> Json.Num(sampling.topP),
      "top_k"            -> Json.Num(sampling.topK),
      "min_p"            -> Json.Num(sampling.minP),
      "presence_penalty" -> Json.Num(sampling.presencePenalty)
    )
    // Ask the server to emit a final usage chunk so we can report token counts
    // and prompt/generation throughput.
    val withUsage =
      if (stream) base :+ ("stream_options" -> Json.Obj("include_usage" -> Json.Bool(true)))
      else base
    val withTools = toolsJson.flatMap(_.fromJson[Json].toOption) match {
      case Some(arr) => withUsage :+ ("tools" -> arr)
      case None      => withUsage
    }
    Json.Obj(Chunk.fromIterable(withTools)).toJson
  }

  private def encodeMessage(m: ChatMessage): Json = {
    val fields = scala.collection.mutable.ListBuffer[(String, Json)]("role" -> Json.Str(m.role))
    // content must always be present; OpenAI accepts empty string for assistant
    // messages that only carry tool_calls.
    fields += ("content" -> Json.Str(m.content.getOrElse("")))
    m.toolCallId.foreach(id => fields += ("tool_call_id" -> Json.Str(id)))
    if (m.toolCalls.nonEmpty) {
      val calls = m.toolCalls.map { tc =>
        Json.Obj(
          "id"       -> Json.Str(tc.id),
          "type"     -> Json.Str("function"),
          "function" -> Json.Obj(
            "name"      -> Json.Str(tc.name),
            "arguments" -> Json.Str(tc.arguments)
          )
        )
      }
      fields += ("tool_calls" -> Json.Arr(Chunk.fromIterable(calls)))
    }
    Json.Obj(Chunk.fromIterable(fields.toList))
  }

  /** Parse one OpenAI SSE `data:` payload (the JSON after `data: `) into a
   *  ChatChunk. Returns None for `[DONE]` or unparseable frames. */
  def parseChunk(dataJson: String): Option[ChatChunk] = {
    if (dataJson.trim == "[DONE]") None
    else dataJson.fromJson[Json].toOption.flatMap { json =>
      val usage   = field(json, "usage").flatMap(parseUsage)
      val timings = field(json, "timings").orElse(field(json, "stats")).flatMap(parseTimings)
      val choice  = field(json, "choices").flatMap(firstElem).map { first =>
        val delta      = field(first, "delta")
        val content    = delta.flatMap(strField(_, "content"))
        val reasoning  = delta.flatMap(strField(_, "reasoning_content"))
        val finish     = strField(first, "finish_reason")
        val toolDeltas = delta.flatMap(field(_, "tool_calls")).map(parseToolDeltas).getOrElse(Nil)
        ChatChunk(content, reasoning, toolDeltas, finish, usage, timings)
      }
      // The include_usage terminal chunk has an empty `choices` array but carries
      // `usage` (and sometimes `timings`); surface it so the loop can record it.
      choice.orElse(
        if (usage.isDefined || timings.isDefined) Some(ChatChunk(None, None, Nil, None, usage, timings))
        else None
      )
    }
  }

  private def parseUsage(json: Json): Option[ChatUsage] =
    Some(ChatUsage(intField(json, "prompt_tokens"), intField(json, "completion_tokens")))

  private def parseTimings(json: Json): Option[ChatTimings] = {
    val pp = doubleField(json, "prompt_per_second").orElse(doubleField(json, "tokens_per_second_prompt"))
    val tg = doubleField(json, "predicted_per_second")
      .orElse(doubleField(json, "tokens_per_second"))
      .orElse(doubleField(json, "generation_tokens_per_second"))
    if (pp.isEmpty && tg.isEmpty) None else Some(ChatTimings(pp, tg))
  }

  private def doubleField(json: Json, name: String): Option[Double] =
    field(json, name).collect { case Json.Num(n) => n.doubleValue }

  private def field(json: Json, name: String): Option[Json] = json match {
    case Json.Obj(fields) => fields.collectFirst { case (k, v) if k == name => v }
    case _                => None
  }

  private def firstElem(json: Json): Option[Json] = json match {
    case Json.Arr(elems) => elems.headOption
    case _               => None
  }

  private def strField(json: Json, name: String): Option[String] =
    field(json, name).collect { case Json.Str(s) => s }

  private def intField(json: Json, name: String): Option[Int] =
    field(json, name).collect { case Json.Num(n) => n.intValue }

  private def parseToolDeltas(json: Json): List[ToolCallDelta] = json match {
    case Json.Arr(elems) =>
      elems.toList.map { e =>
        val index = intField(e, "index").getOrElse(0)
        val id    = strField(e, "id")
        val fn    = field(e, "function")
        val name  = fn.flatMap(strField(_, "name"))
        val args  = fn.flatMap(strField(_, "arguments"))
        ToolCallDelta(index, id, name, args)
      }
    case _ => Nil
  }
}
