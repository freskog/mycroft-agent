package dev.freskog.agent.mycroft.tools

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.json._
import zio.json.ast.Json
import zio.stream.ZStream

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Small typed HTTP client for mycroft's own bookkeeping against person-service:
 *  context bundles, the household graph, channels, and the message log. This is
 *  NOT the agent tool surface (that is `safe_run`); it is mycroft's internal
 *  plumbing. */
trait PersonClient {
  def contextBundle(personId: Option[PersonId], factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle]
  def searchMemory(query: String, personId: Option[PersonId], limit: Int): IO[AgentError, List[MemoryHit]]
  def profileFacts(limit: Int): IO[AgentError, List[MemoryItem]]
  def household: IO[AgentError, HouseholdGraph]
  /** Open goals (the durable outcomes the household is working toward), injected
   *  into the turn so the agent is aware of them. */
  def openGoals: IO[AgentError, List[Goal]]
  def getChannel(id: ChannelId): IO[AgentError, Option[ChannelWithMembers]]
  def listChannels: IO[AgentError, List[Channel]]
  def createChannel(id: ChannelId, defaultModel: Option[String], members: List[PersonId]): IO[AgentError, ChannelWithMembers]
  def appendMessage(channelId: ChannelId, role: MessageRole, from: Option[PersonId], content: String, toolCallsJson: Option[String], externalId: Option[String]): IO[AgentError, Message]
  def listMessages(channelId: ChannelId, since: Option[Instant], limit: Int): IO[AgentError, List[Message]]
  def logEvent(actor: String, action: String, category: String, text: Option[String], payloadJson: Option[String]): IO[AgentError, Unit]
  /** Subscribe to person-service's approval-lifecycle SSE stream. The stream ends
   *  on connection close (the caller is expected to retry/reconnect). */
  def subscribeApprovals(person: Option[PersonId]): ZStream[Any, AgentError, ApprovalEvent]
}

object PersonClient {

  def live(baseUrl: String): PersonClient = new PersonClient {

    private val client: JHttpClient = JHttpClient.newBuilder()
      .version(JHttpClient.Version.HTTP_1_1)
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .build()

    def contextBundle(personId: Option[PersonId], factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle] = {
      val params = Map("fact_limit" -> factLimit.toString, "event_limit" -> eventLimit.toString) ++
        personId.map("person" -> _.value)
      get("/memory/context", params).flatMap(decode[ContextBundle])
    }

    def searchMemory(query: String, personId: Option[PersonId], limit: Int): IO[AgentError, List[MemoryHit]] = {
      val params = Map("q" -> query, "limit" -> limit.toString) ++ personId.map("person" -> _.value)
      get("/memory/search", params).flatMap(decode[List[MemoryHit]])
    }

    def profileFacts(limit: Int): IO[AgentError, List[MemoryItem]] =
      get("/memory/profile", Map("limit" -> limit.toString)).flatMap(decode[List[MemoryItem]])

    def household: IO[AgentError, HouseholdGraph] =
      get("/household", Map.empty).flatMap(decode[HouseholdGraph])

    def openGoals: IO[AgentError, List[Goal]] =
      get("/goals", Map("status" -> "open")).flatMap(decode[List[Goal]])

    def getChannel(id: ChannelId): IO[AgentError, Option[ChannelWithMembers]] =
      get(s"/channels/${id.value}", Map.empty).flatMap(decode[ChannelWithMembers]).map(Some(_))
        .catchSome { case _: AgentError.NotFound => ZIO.none }

    def listChannels: IO[AgentError, List[Channel]] =
      get("/channels", Map.empty).flatMap(decode[List[Channel]])

    def createChannel(id: ChannelId, defaultModel: Option[String], members: List[PersonId]): IO[AgentError, ChannelWithMembers] = {
      val body = Json.Obj(
        "id"           -> Json.Str(id.value),
        "defaultModel" -> defaultModel.map(Json.Str(_)).getOrElse(Json.Null),
        "members"      -> Json.Arr(Chunk.fromIterable(members.map(p => Json.Str(p.value))))
      ).toJson
      post("/channels", body).flatMap(decode[ChannelWithMembers])
    }

    def appendMessage(channelId: ChannelId, role: MessageRole, from: Option[PersonId], content: String, toolCallsJson: Option[String], externalId: Option[String]): IO[AgentError, Message] = {
      val body = Json.Obj(
        "channelId"     -> Json.Str(channelId.value),
        "role"          -> Json.Str(MessageRole.asString(role)),
        "personIdFrom"  -> from.map(p => Json.Str(p.value)).getOrElse(Json.Null),
        "content"       -> Json.Str(content),
        "toolCallsJson" -> toolCallsJson.map(Json.Str(_)).getOrElse(Json.Null),
        "externalId"    -> externalId.map(Json.Str(_)).getOrElse(Json.Null)
      ).toJson
      post("/messages", body).flatMap(decode[Message])
    }

    def listMessages(channelId: ChannelId, since: Option[Instant], limit: Int): IO[AgentError, List[Message]] = {
      val params = Map("channel" -> channelId.value, "limit" -> limit.toString) ++
        since.map(s => "since" -> s.toString)
      get("/messages", params).flatMap(decode[List[Message]])
    }

    def logEvent(actor: String, action: String, category: String, text: Option[String], payloadJson: Option[String]): IO[AgentError, Unit] = {
      val body = Json.Obj(
        "actor"       -> Json.Str(actor),
        "action"      -> Json.Str(action),
        "category"    -> Json.Str(category),
        "targetType"  -> Json.Null,
        "targetId"    -> Json.Null,
        "text"        -> text.map(Json.Str(_)).getOrElse(Json.Null),
        "payloadJson" -> payloadJson.map(Json.Str(_)).getOrElse(Json.Null)
      ).toJson
      post("/events", body).unit
    }

    def subscribeApprovals(person: Option[PersonId]): ZStream[Any, AgentError, ApprovalEvent] = {
      val query = person.map(p => s"?person=${enc(p.value)}").getOrElse("")
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl/approvals/stream$query"))
        .GET().header("Accept", "text/event-stream").build()
      ZStream.unwrapScoped {
        ZIO.acquireRelease(
          ZIO.attemptBlocking(client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body())
            .mapError(t => AgentError.HttpFailed(s"GET /approvals/stream: ${errMsg(t)}", Some(t)))
        )(is => ZIO.attempt(is.close()).ignore).map { is =>
          val reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
          // Each SSE frame's `data:` line is the full ApprovalEvent JSON; the
          // `event:`/`: comment` lines are skipped. EOF ends the stream.
          ZStream.repeatZIOOption {
            ZIO.attemptBlocking(Option(nextDataLine(reader)))
              .mapError(t => Option(AgentError.HttpFailed(s"reading approvals stream: ${errMsg(t)}", Some(t))))
              .flatMap {
                case None       => ZIO.fail(None)
                case Some(json) => ZIO.fromEither(json.fromJson[ApprovalEvent])
                                     .mapError(e => Option(AgentError.DecodeFailed(s"approval event: $e")))
              }
          }
        }
      }
    }

    /** Read forward to the next `data:` line and return its payload, or null at EOF. */
    private def nextDataLine(reader: BufferedReader): String = {
      var line = reader.readLine()
      while (line != null && !line.startsWith("data:")) line = reader.readLine()
      if (line == null) null else line.stripPrefix("data:").trim
    }

    private def errMsg(t: Throwable): String = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)

    // --- HTTP plumbing ---

    private def get(path: String, params: Map[String, String]): IO[AgentError, String] = {
      val query = if (params.isEmpty) "" else "?" + params.map { case (k, v) => s"$k=${enc(v)}" }.mkString("&")
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path$query"))
        .GET().header("Accept", "application/json").build()
      send(request, s"GET $path")
    }

    private def post(path: String, body: String): IO[AgentError, String] = {
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json").header("Accept", "application/json").build()
      send(request, s"POST $path")
    }

    private def send(request: HttpRequest, context: String): IO[AgentError, String] =
      ZIO.attemptBlocking(client.send(request, HttpResponse.BodyHandlers.ofString()))
        .mapError(t => AgentError.HttpFailed(s"$context: ${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)}", Some(t)))
        .flatMap { response =>
          val status = response.statusCode()
          val body   = response.body()
          if (status == 404) ZIO.fail(AgentError.NotFound("resource", context))
          else if (status >= 400) ZIO.fail(AgentError.HttpBadStatus(s"$context returned $status", status, body))
          else ZIO.succeed(body)
        }

    private def decode[A: JsonDecoder](body: String): IO[AgentError, A] =
      ZIO.fromEither(body.fromJson[A]).mapError(e => AgentError.DecodeFailed(s"decoding response: $e"))

    private def enc(v: String): String =
      java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)
  }
}
