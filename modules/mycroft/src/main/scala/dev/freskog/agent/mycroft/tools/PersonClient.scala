package dev.freskog.agent.mycroft.tools

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.json._
import zio.json.ast.Json

import java.net.URI
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}
import java.time.Instant

/** Small typed HTTP client for mycroft's own bookkeeping against person-service:
 *  scope-roles, context bundles, channels, and the message log. This is NOT the
 *  agent tool surface (that is `shell_run`); it is mycroft's internal plumbing. */
trait PersonClient {
  def scopeRoles(personId: PersonId): IO[AgentError, List[PersonScopeRole]]
  def contextBundle(scopeId: ScopeId, factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle]
  def getChannel(id: ChannelId): IO[AgentError, Option[ChannelWithMembers]]
  def listChannels: IO[AgentError, List[Channel]]
  def createChannel(id: ChannelId, defaultModel: Option[String], members: List[PersonId]): IO[AgentError, ChannelWithMembers]
  def appendMessage(channelId: ChannelId, role: MessageRole, from: Option[PersonId], content: String, toolCallsJson: Option[String], externalId: Option[String]): IO[AgentError, Message]
  def listMessages(channelId: ChannelId, since: Option[Instant], limit: Int): IO[AgentError, List[Message]]
  def logEvent(actor: String, action: String, category: String, scopeId: Option[ScopeId], text: Option[String], payloadJson: Option[String]): IO[AgentError, Unit]
}

object PersonClient {

  def live(baseUrl: String): PersonClient = new PersonClient {

    private val client: JHttpClient = JHttpClient.newBuilder()
      .version(JHttpClient.Version.HTTP_1_1)
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .build()

    def scopeRoles(personId: PersonId): IO[AgentError, List[PersonScopeRole]] =
      get("/scope-roles", Map("person" -> personId.value)).flatMap(decode[List[PersonScopeRole]])

    def contextBundle(scopeId: ScopeId, factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle] =
      get("/memory/context", Map("scope" -> scopeId.value, "fact_limit" -> factLimit.toString, "event_limit" -> eventLimit.toString))
        .flatMap(decode[ContextBundle])

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

    def logEvent(actor: String, action: String, category: String, scopeId: Option[ScopeId], text: Option[String], payloadJson: Option[String]): IO[AgentError, Unit] = {
      val body = Json.Obj(
        "actor"       -> Json.Str(actor),
        "action"      -> Json.Str(action),
        "category"    -> Json.Str(category),
        "scopeId"     -> scopeId.map(s => Json.Str(s.value)).getOrElse(Json.Null),
        "targetType"  -> Json.Null,
        "targetId"    -> Json.Null,
        "text"        -> text.map(Json.Str(_)).getOrElse(Json.Null),
        "payloadJson" -> payloadJson.map(Json.Str(_)).getOrElse(Json.Null)
      ).toJson
      post("/events", body).unit
    }

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
