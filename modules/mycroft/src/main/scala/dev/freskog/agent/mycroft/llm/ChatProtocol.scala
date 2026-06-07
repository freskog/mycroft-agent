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

final case class ChatChunk(
  contentDelta: Option[String],
  reasoningDelta: Option[String],
  toolCallDeltas: List[ToolCallDelta],
  finishReason: Option[String]
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
    val withTools = toolsJson.flatMap(_.fromJson[Json].toOption) match {
      case Some(arr) => base :+ ("tools" -> arr)
      case None      => base
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
      field(json, "choices").flatMap(firstElem).map { first =>
        val delta      = field(first, "delta")
        val content    = delta.flatMap(strField(_, "content"))
        val reasoning  = delta.flatMap(strField(_, "reasoning_content"))
        val finish     = strField(first, "finish_reason")
        val toolDeltas = delta.flatMap(field(_, "tool_calls")).map(parseToolDeltas).getOrElse(Nil)
        ChatChunk(content, reasoning, toolDeltas, finish)
      }
    }
  }

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
