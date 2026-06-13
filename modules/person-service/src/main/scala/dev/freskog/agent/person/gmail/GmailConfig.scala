package dev.freskog.agent.person.gmail

import zio.json._
import zio.json.ast.Json

import java.nio.file.{Files, Paths}
import scala.io.Source

object GmailConfig {

  val ProviderName = "gmail"
  val ReadonlyScope = "https://www.googleapis.com/auth/gmail.readonly"
  val CalendarReadonlyScope = "https://www.googleapis.com/auth/calendar.readonly"
  // Read AND write to calendar events (supersedes calendar.readonly for our use —
  // events.list works under this scope too).
  val CalendarEventsScope = "https://www.googleapis.com/auth/calendar.events"
  // Read/compose/send Gmail (supersedes gmail.readonly). Requested now so a single
  // consent also covers future *gated* email actions; nothing in the code can send
  // yet (no send executor), and the token lives only in the trusted core.
  val ModifyScope = "https://www.googleapis.com/auth/gmail.modify"
  // Read/write Google Tasks — the projection of commitments (todos) the user views
  // and ticks on any device, with status/due synced back.
  val TasksScope = "https://www.googleapis.com/auth/tasks"

  /** Scopes requested in one Google consent. A single grant yields one
   *  access/refresh token that works against the Gmail, Calendar, and Tasks APIs,
   *  so we keep a single credential row rather than separate OAuth flows. Changing
   *  this set requires a one-time re-consent (operator re-runs the Gmail auth flow):
   *  tokens minted under the old scopes won't carry new permissions (e.g. Tasks
   *  write or calendarList read 403s). `calendar.readonly` is included so we can
   *  enumerate the owner's calendars (the HITL picker) and fan the agenda/sync-back
   *  across all of them — `calendar.events` alone can't list calendars. */
  val RequestedScopes = s"$ModifyScope $CalendarEventsScope $CalendarReadonlyScope $TasksScope"

  // --- Dedicated sender account (e.g. mycroft.agent@gmail.com) ---
  // A separate, send-only credential so briefings are sent *as the agent*, not the
  // owner. `userinfo.email` lets us read the account address (the message From must
  // match the authenticated account). Stored under its own provider/owner so it
  // never collides with an owner's read credential.
  val SendScope          = "https://www.googleapis.com/auth/gmail.send"
  val UserinfoEmailScope = "https://www.googleapis.com/auth/userinfo.email"
  val SenderScopes       = s"$SendScope $UserinfoEmailScope"
  // Stored as a distinct provider, keyed to a real owner (the credentials FK
  // requires a real person). The OAuth `state` is `SenderStatePrefix + ownerId`.
  val SenderProvider     = "gmail-sender"
  val SenderStatePrefix  = "__sender__:"
  val UserinfoEndpoint   = "https://www.googleapis.com/oauth2/v3/userinfo"

  val AuthEndpoint  = "https://accounts.google.com/o/oauth2/v2/auth"
  val TokenEndpoint = "https://oauth2.googleapis.com/token"
  val ApiBase       = "https://gmail.googleapis.com/gmail/v1"
  val TasksApiBase  = "https://tasks.googleapis.com/tasks/v1"

  /** Server-side OAuth redirect target. Google redirects the operator's browser
   *  to person-service's `GET /gmail/oauth/callback`, which completes the exchange.
   *  Override with GMAIL_REDIRECT_URI; must match a Google Cloud redirect URI. */
  val DefaultRedirectUri = "http://localhost:8080/gmail/oauth/callback"

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
