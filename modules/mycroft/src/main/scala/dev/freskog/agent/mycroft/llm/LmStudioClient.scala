package dev.freskog.agent.mycroft.llm

import dev.freskog.agent.common.AgentError

import zio._
import zio.stream._
import zio.json.ast.Json

import java.net.URI
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}

/** OpenAI-compatible client for LM Studio. Streaming completions are read with
 *  `BodyHandlers.ofLines()` on the blocking pool and demuxed by StreamParser. */
trait LmStudioClient {
  def chat(model: String, messages: List[ChatMessage], maxTokens: Int, toolsJson: Option[String]): ZStream[Any, AgentError, ChatChunk]
  def listModels: IO[AgentError, List[String]]
}

object LmStudioClient {

  def live(baseUrl: String): LmStudioClient = new LmStudioClient {

    // Force HTTP/1.1: LM Studio's server mishandles Java's default HTTP/2 (h2c)
    // upgrade attempt over plaintext, which manifests as a hang.
    private val client: JHttpClient = JHttpClient.newBuilder()
      .version(JHttpClient.Version.HTTP_1_1)
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .build()

    def chat(model: String, messages: List[ChatMessage], maxTokens: Int, toolsJson: Option[String]): ZStream[Any, AgentError, ChatChunk] = {
      val body = ChatProtocol.encodeBody(model, messages, maxTokens, stream = true, toolsJson)
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl/v1/chat/completions"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .build()

      val openStream: IO[AgentError, java.util.stream.Stream[String]] =
        ZIO.attemptBlocking {
          val resp = client.send(request, HttpResponse.BodyHandlers.ofLines())
          if (resp.statusCode() >= 400)
            throw new RuntimeException(s"LM Studio returned ${resp.statusCode()}")
          resp.body()
        }.mapError(t => AgentError.HttpFailed(s"LM Studio chat: ${msg(t)}", Some(t)))

      ZStream
        .fromZIO(openStream)
        .flatMap { s =>
          // chunkSize = 1: emit each SSE line the instant it is read. The default
          // (4096) makes `fromIterator` accumulate a whole chunk of *blocking*
          // line reads before passing anything downstream, which collapses
          // token-by-token streaming into one burst at the end of generation.
          ZStream
            .blocking(ZStream.fromJavaStream(s, 1))
            .mapError(t => AgentError.HttpFailed(s"LM Studio stream: ${msg(t)}", Some(t)))
        }
        .map(StreamParser.parseLine)
        .collect { case Some(chunk) => chunk }
    }

    def listModels: IO[AgentError, List[String]] = {
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl/v1/models"))
        .GET()
        .header("Accept", "application/json")
        .build()
      ZIO.attemptBlocking(client.send(request, HttpResponse.BodyHandlers.ofString()))
        .mapError(t => AgentError.HttpFailed(s"LM Studio models: ${msg(t)}", Some(t)))
        .flatMap { resp =>
          if (resp.statusCode() >= 400)
            ZIO.fail(AgentError.HttpBadStatus("LM Studio /v1/models", resp.statusCode(), resp.body()))
          else ZIO.succeed(parseModelIds(resp.body()))
        }
    }

    private def parseModelIds(body: String): List[String] = {
      import zio.json._
      body.fromJson[Json].toOption.flatMap {
        case obj @ Json.Obj(_) =>
          obj.fields.collectFirst { case ("data", Json.Arr(elems)) => elems }.map { elems =>
            elems.toList.flatMap {
              case Json.Obj(fields) => fields.collectFirst { case ("id", Json.Str(id)) => id }
              case _                => None
            }
          }
        case _ => None
      }.getOrElse(Nil)
    }

    private def msg(t: Throwable): String =
      Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
  }
}
