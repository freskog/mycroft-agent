package dev.freskog.agent.person.gmail

import dev.freskog.agent.common.AgentError

import zio._
import zio.json._
import zio.json.ast.Json

import java.net.URLEncoder
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

object GmailClient {

  private val client: JHttpClient = JHttpClient.newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(30))
    .build()

  def listMessageIds(accessToken: String, query: String, maxResults: Int): IO[AgentError, List[String]] =
    getJson(accessToken, s"/users/me/messages?q=${encode(query)}&maxResults=$maxResults").flatMap { json =>
      ZIO.succeed {
        json match {
          case Json.Obj(fields) =>
            fields.collectFirst {
              case ("messages", Json.Arr(arr)) =>
                arr.collect { case Json.Obj(mf) => mf.collectFirst { case ("id", Json.Str(id)) => id } }.flatten.toList
            }.getOrElse(Nil)
          case _ => Nil
        }
      }
    }

  def getMessage(accessToken: String, messageId: String): IO[AgentError, Option[MessageParser.ParsedMessage]] =
    getJson(accessToken, s"/users/me/messages/$messageId?format=full")
      .map(MessageParser.parseMessage)

  /** Download one attachment's raw bytes. The Gmail attachments endpoint returns
   *  `{ size, data }` where `data` is base64url-encoded. */
  def getAttachment(accessToken: String, messageId: String, attachmentId: String): IO[AgentError, Array[Byte]] =
    getJson(accessToken, s"/users/me/messages/$messageId/attachments/$attachmentId").flatMap { json =>
      val data = json match {
        case Json.Obj(fields) => fields.collectFirst { case ("data", Json.Str(d)) => d }
        case _                => None
      }
      data match {
        case Some(d) => ZIO.attempt(decodeBase64Url(d)).mapError(t => AgentError.DecodeFailed(s"attachment data: ${Option(t.getMessage).getOrElse("decode error")}"))
        case None    => ZIO.fail(AgentError.NotFound("attachment", attachmentId))
      }
    }

  private def decodeBase64Url(s: String): Array[Byte] = {
    val padded = s + ("=" * ((4 - s.length % 4) % 4))
    java.util.Base64.getUrlDecoder.decode(padded)
  }

  /** Send a plain-text email via Gmail (`messages.send`, needs the gmail.modify
   *  scope). Subject + body are MIME base64-encoded so UTF-8 (names, emoji) is
   *  safe. Used only by person-service delivery — never exposed to the agent. */
  def sendMessage(
    accessToken: String,
    from: String,
    to: String,
    subject: String,
    body: String,
    contentType: String = "text/plain; charset=UTF-8"
  ): IO[AgentError, Unit] = {
    val b64    = (s: String) => java.util.Base64.getEncoder.encodeToString(s.getBytes(StandardCharsets.UTF_8))
    val mime   =
      s"From: $from\r\nTo: $to\r\n" +
        s"Subject: =?UTF-8?B?${b64(subject)}?=\r\n" +
        s"MIME-Version: 1.0\r\nContent-Type: $contentType\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
        b64(body)
    val raw    = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(mime.getBytes(StandardCharsets.UTF_8))
    postJson(accessToken, "/users/me/messages/send", Json.Obj("raw" -> Json.Str(raw)).toJson).unit
  }

  private def postJson(accessToken: String, path: String, body: String): IO[AgentError, Json] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder()
        .uri(java.net.URI.create(GmailConfig.ApiBase + path))
        .header("Authorization", s"Bearer $accessToken")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 400)
        throw new RuntimeException(s"Gmail API ${resp.statusCode()}: ${resp.body()}")
      resp.body()
    }.mapError(t => AgentError.HttpFailed(s"Gmail: ${Option(t.getMessage).getOrElse("error")}", Some(t)))
      .flatMap(b => ZIO.fromEither(b.fromJson[Json]).mapError(msg => AgentError.DecodeFailed(msg)))

  private def getJson(accessToken: String, path: String): IO[AgentError, Json] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder()
        .uri(java.net.URI.create(GmailConfig.ApiBase + path))
        .header("Authorization", s"Bearer $accessToken")
        .GET()
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 400)
        throw new RuntimeException(s"Gmail API ${resp.statusCode()}: ${resp.body()}")
      resp.body()
    }.mapError(t => AgentError.HttpFailed(s"Gmail: ${Option(t.getMessage).getOrElse("error")}", Some(t)))
      .flatMap(body => ZIO.fromEither(body.fromJson[Json]).mapError(msg => AgentError.DecodeFailed(msg)))

  private def encode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)
}
