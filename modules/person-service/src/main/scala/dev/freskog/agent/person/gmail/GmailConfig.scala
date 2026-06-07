package dev.freskog.agent.person.gmail

import zio.json._
import zio.json.ast.Json

import java.nio.file.{Files, Paths}
import scala.io.Source

object GmailConfig {

  val ProviderName = "gmail"
  val ReadonlyScope = "https://www.googleapis.com/auth/gmail.readonly"
  val CalendarReadonlyScope = "https://www.googleapis.com/auth/calendar.readonly"

  /** Scopes requested in one Google consent. A single grant yields one
   *  access/refresh token that works against both the Gmail and Calendar APIs,
   *  so we keep a single `gmail` credential row rather than a second OAuth flow. */
  val RequestedScopes = s"$ReadonlyScope $CalendarReadonlyScope"

  val AuthEndpoint  = "https://accounts.google.com/o/oauth2/v2/auth"
  val TokenEndpoint = "https://oauth2.googleapis.com/token"
  val ApiBase       = "https://gmail.googleapis.com/gmail/v1"

  /** Loopback callback used by `person gmail auth` (must match Google Cloud redirect URIs). */
  val DefaultRedirectUri = "http://localhost:8765/oauth/callback"

  final case class Settings(
    clientId: String,
    clientSecret: String,
    redirectUri: String
  )

  /** Load OAuth settings: env vars override, then external file, then classpath resource. */
  def load: Either[String, Settings] =
    fromEnv.orElse(loadFromFile(sys.env.get("GMAIL_CLIENT_SECRET_FILE")).flatMap {
      case Some(s) => Right(s)
      case None    => loadFromClasspath
    })

  /** @deprecated use [[load]] */
  def fromEnv: Either[String, Settings] = {
    val clientId     = sys.env.get("GMAIL_CLIENT_ID")
    val clientSecret = sys.env.get("GMAIL_CLIENT_SECRET")
    val redirectUri  = redirectUriFromEnv
    (clientId, clientSecret) match {
      case (Some(id), Some(secret)) => Right(Settings(id, secret, redirectUri))
      case _                        => Left("GMAIL_CLIENT_ID and GMAIL_CLIENT_SECRET not set")
    }
  }

  private def redirectUriFromEnv: String =
    sys.env.getOrElse("GMAIL_REDIRECT_URI", DefaultRedirectUri)

  private def loadFromClasspath: Either[String, Settings] =
    Option(getClass.getResourceAsStream("/client-secret.json")) match {
      case None    => Left("No Gmail credentials: set GMAIL_CLIENT_ID/GMAIL_CLIENT_SECRET, GMAIL_CLIENT_SECRET_FILE, or add client-secret.json to classpath")
      case Some(is) =>
        val raw =
          try Source.fromInputStream(is, "UTF-8").mkString
          finally is.close()
        parseClientSecretJson(raw, redirectUriFromEnv)
    }

  private def loadFromFile(pathOpt: Option[String]): Either[String, Option[Settings]] =
    pathOpt match {
      case None => Right(None)
      case Some(path) =>
        if (!Files.isRegularFile(Paths.get(path)))
          Left(s"GMAIL_CLIENT_SECRET_FILE not found: $path")
        else {
          val raw = new String(Files.readAllBytes(Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8)
          parseClientSecretJson(raw, redirectUriFromEnv).map(Some(_))
        }
    }

  /** Parse Google OAuth client JSON (`installed` for desktop, `web` for web app). */
  private[person] def parseClientSecretJson(raw: String, redirectUri: String): Either[String, Settings] =
    raw.fromJson[Json].left.map(_.toString).flatMap(parseClientSecretJson(_, redirectUri))

  private def parseClientSecretJson(json: Json, redirectUri: String): Either[String, Settings] = json match {
    case Json.Obj(root) =>
      val block = root.collectFirst {
        case ("installed", o: Json.Obj) => o.fields
        case ("web", o: Json.Obj)       => o.fields
      }
      block match {
        case None => Left("client-secret.json: expected top-level 'installed' or 'web' object")
        case Some(fields) =>
          for {
            id     <- fieldStr(fields, "client_id")
            secret <- fieldStr(fields, "client_secret")
          } yield Settings(id, secret, redirectUri)
      }
    case _ => Left("client-secret.json: expected JSON object")
  }

  private def fieldStr(fields: Iterable[(String, Json)], name: String): Either[String, String] =
    fields.collectFirst { case (k, Json.Str(v)) if k == name => v }
      .toRight(s"client-secret.json: missing '$name'")
}
