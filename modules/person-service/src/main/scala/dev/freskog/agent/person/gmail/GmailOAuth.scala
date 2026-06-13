package dev.freskog.agent.person.gmail

import dev.freskog.agent.common.AgentError

import zio._
import zio.json._
import zio.json.ast.Json

import java.net.URLEncoder
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Instant

object GmailOAuth {

  final case class TokenResponse(
    accessToken: String,
    refreshToken: Option[String],
    expiresIn: Long,
    scope: String,
    tokenType: String
  )

  final case class UserInfo(email: String)

  def authUrl(settings: GmailConfig.Settings, state: String, scopes: String = GmailConfig.RequestedScopes): String = {
    val params = List(
      "client_id"     -> settings.clientId,
      "redirect_uri"  -> settings.redirectUri,
      "response_type" -> "code",
      "scope"         -> scopes,
      "access_type"   -> "offline",
      "prompt"        -> "consent",
      "state"         -> state
    )
    params.map { case (k, v) => s"$k=${encode(v)}" }.mkString(GmailConfig.AuthEndpoint + "?", "&", "")
  }

  /** The authenticated account's email via the OpenID userinfo endpoint (works with
   *  the `userinfo.email` scope — the Gmail profile endpoint needs a Gmail scope the
   *  send-only sender credential doesn't have). */
  def fetchAccountEmail(accessToken: String): IO[AgentError, UserInfo] =
    getJson(GmailConfig.UserinfoEndpoint, accessToken).flatMap { json =>
      ZIO.fromEither(strField(json, "email"))
        .mapBoth(_ => AgentError.DecodeFailed("userinfo missing email"), UserInfo)
    }

  def exchangeCode(settings: GmailConfig.Settings, code: String): IO[AgentError, TokenResponse] =
    postForm(
      GmailConfig.TokenEndpoint,
      Map(
        "code"          -> code,
        "client_id"     -> settings.clientId,
        "client_secret" -> settings.clientSecret,
        "redirect_uri"  -> settings.redirectUri,
        "grant_type"    -> "authorization_code"
      )
    ).flatMap(decodeToken)

  def refreshAccessToken(settings: GmailConfig.Settings, refreshToken: String): IO[AgentError, TokenResponse] =
    postForm(
      GmailConfig.TokenEndpoint,
      Map(
        "refresh_token" -> refreshToken,
        "client_id"     -> settings.clientId,
        "client_secret" -> settings.clientSecret,
        "grant_type"    -> "refresh_token"
      )
    ).flatMap(decodeToken)

  def fetchUserEmail(accessToken: String): IO[AgentError, UserInfo] =
    getJson(s"${GmailConfig.ApiBase}/users/me/profile", accessToken).flatMap { json =>
      ZIO.fromEither(strField(json, "emailAddress"))
        .mapBoth(_ => AgentError.DecodeFailed("gmail profile missing emailAddress"), UserInfo)
    }

  def expiresAtFrom(token: TokenResponse, now: Instant): Instant =
    now.plusSeconds(token.expiresIn)

  private def decodeToken(body: String): IO[AgentError, TokenResponse] =
    ZIO.fromEither(body.fromJson[Json]).mapError(msg => AgentError.DecodeFailed(msg)).flatMap { json =>
      for {
        access <- field(json, "access_token")
        refresh = optStr(json, "refresh_token")
        expires <- field(json, "expires_in").flatMap(s => ZIO.attempt(s.toLong).mapError(_ => AgentError.DecodeFailed("invalid expires_in")))
        scope   <- field(json, "scope").catchAll(_ => ZIO.succeed(GmailConfig.ReadonlyScope))
        ttype   <- field(json, "token_type").catchAll(_ => ZIO.succeed("Bearer"))
      } yield TokenResponse(access, refresh, expires, scope, ttype)
    }

  private def postForm(url: String, form: Map[String, String]): IO[AgentError, String] =
    ZIO.attemptBlocking {
      val body = form.map { case (k, v) => s"$k=${encode(v)}" }.mkString("&")
      val req = HttpRequest.newBuilder()
        .uri(java.net.URI.create(url))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = JHttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 400)
        throw new RuntimeException(s"OAuth token request failed (${resp.statusCode()}): ${resp.body()}")
      resp.body()
    }.mapError(t => AgentError.HttpFailed(s"OAuth: ${Option(t.getMessage).getOrElse("error")}", Some(t)))

  private def getJson(url: String, accessToken: String): IO[AgentError, Json] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder()
        .uri(java.net.URI.create(url))
        .header("Authorization", s"Bearer $accessToken")
        .GET()
        .build()
      val resp = JHttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 400)
        throw new RuntimeException(s"Gmail API GET failed (${resp.statusCode()}): ${resp.body()}")
      resp.body()
    }.mapError(t => AgentError.HttpFailed(s"Gmail API: ${Option(t.getMessage).getOrElse("error")}", Some(t)))
      .flatMap(body => ZIO.fromEither(body.fromJson[Json]).mapError(msg => AgentError.DecodeFailed(msg)))

  private def field(json: Json, name: String): IO[AgentError, String] =
    ZIO.fromEither(strField(json, name)).mapError(_ => AgentError.DecodeFailed(s"missing $name in OAuth response"))

  private def strField(json: Json, name: String): Either[String, String] = json match {
    case Json.Obj(fields) =>
      fields.collectFirst {
        case (k, Json.Str(s)) if k == name => s
        case (k, Json.Num(n)) if k == name => n.toString
      }.toRight(s"missing $name")
    case _ => Left(s"missing $name")
  }

  private def optStr(json: Json, name: String): Option[String] = json match {
    case Json.Obj(fields) => fields.collectFirst { case (k, Json.Str(s)) if k == name => s }
    case _                => None
  }

  private def encode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)
}
