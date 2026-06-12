package dev.freskog.agent.person.calendar

import dev.freskog.agent.common.{AgentError, CalendarEvent}
import dev.freskog.agent.person.domain.CalendarCreateEventRequest

import zio._
import zio.json._
import zio.json.ast.Json

import java.net.URLEncoder
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}

/** Minimal read-only Google Calendar client. Phase 1 reads the owner's primary
 *  calendar; multi-calendar support (calendarList.list + per-calendar fan-out)
 *  is a later extension. Uses the same Google OAuth access token as Gmail. */
object CalendarClient {

  val ApiBase = "https://www.googleapis.com/calendar/v3"

  private val client: JHttpClient = JHttpClient.newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(30))
    .build()

  /** List events on `calendarId` between `timeMin` and `timeMax`, expanding
   *  recurring events into instances and sorting by start time. */
  def listEvents(
    accessToken: String,
    calendarId: String,
    timeMin: Instant,
    timeMax: Instant,
    maxResults: Int
  ): IO[AgentError, List[CalendarEvent]] = {
    val path =
      s"/calendars/${encode(calendarId)}/events" +
        s"?timeMin=${encode(timeMin.toString)}" +
        s"&timeMax=${encode(timeMax.toString)}" +
        s"&singleEvents=true&orderBy=startTime&maxResults=$maxResults"
    getJson(accessToken, path).map(parseEvents(_, calendarId))
  }

  /** List the owner's calendars as `(id, summary)`, primary first. Used to source
   *  the HITL calendar menu and to fan out the agenda across all calendars. Reads
   *  `calendarList.list` (calendar.calendars.readonly, granted in the consent). */
  def listCalendars(accessToken: String): IO[AgentError, List[(String, String)]] =
    getJson(accessToken, "/users/me/calendarList").map(parseCalendarList)

  private def parseCalendarList(json: Json): List[(String, String)] =
    json match {
      case Json.Obj(fields) =>
        val items = fields.collectFirst { case ("items", Json.Arr(xs)) => xs }.getOrElse(Chunk.empty)
        val cals = items.toList.flatMap {
          case Json.Obj(f) =>
            val m = f.toMap
            str(m, "id").map { id =>
              val summary = str(m, "summaryOverride").orElse(str(m, "summary")).getOrElse(id)
              val primary = m.get("primary").contains(Json.Bool(true))
              (id, summary, primary)
            }
          case _ => None
        }
        // Primary first, then alphabetical by label — stable menu order.
        cals.sortBy { case (_, summary, primary) => (if (primary) 0 else 1, summary.toLowerCase) }
          .map { case (id, summary, _) => (id, summary) }
      case _ => Nil
    }

  /** Create an event on `calendarId` and return it as parsed (incl. `htmlLink`). */
  def createEvent(accessToken: String, calendarId: String, req: CalendarCreateEventRequest): IO[AgentError, CalendarEvent] =
    postJson(accessToken, s"/calendars/${encode(calendarId)}/events", buildEventBody(req).toJson)
      .flatMap(json =>
        ZIO.fromOption(parseEvent(json, calendarId))
          .orElseFail(AgentError.DecodeFailed("calendar create: unparseable event in response"))
      )

  /** Map our request to a Google Calendar event resource. Timed events use
   *  `{ dateTime }` (the UTC instant); all-day use `{ date }` (UTC calendar date,
   *  end exclusive per Google's convention). Pure — unit-tested. */
  def buildEventBody(req: CalendarCreateEventRequest): Json = {
    def timeNode(i: Instant): Json =
      if (req.allDay) Json.Obj("date" -> Json.Str(i.atZone(ZoneOffset.UTC).toLocalDate.toString))
      else Json.Obj("dateTime" -> Json.Str(i.toString))
    val base = List(
      "summary" -> Json.Str(req.summary),
      "start"   -> timeNode(req.start),
      "end"     -> timeNode(req.end)
    )
    val withLoc  = req.location.fold(base)(l => base :+ ("location" -> Json.Str(l)))
    val withDesc = req.description.fold(withLoc)(d => withLoc :+ ("description" -> Json.Str(d)))
    Json.Obj(Chunk.fromIterable(withDesc))
  }

  private def parseEvents(json: Json, calendarId: String): List[CalendarEvent] =
    json match {
      case Json.Obj(fields) =>
        fields.collectFirst { case ("items", Json.Arr(items)) => items }
          .getOrElse(Chunk.empty)
          .toList
          .flatMap(parseEvent(_, calendarId))
      case _ => Nil
    }

  private def parseEvent(json: Json, calendarId: String): Option[CalendarEvent] =
    json match {
      case Json.Obj(fields) =>
        val m = fields.toMap
        for {
          id              <- str(m, "id")
          (start, allDay) <- parseTime(m.get("start"))
          (end, _)        <- parseTime(m.get("end")).orElse(Some((start, allDay)))
        } yield CalendarEvent(
          externalId  = id,
          calendarId  = calendarId,
          summary     = str(m, "summary").getOrElse("(no title)"),
          start       = start,
          end         = end,
          allDay      = allDay,
          location    = str(m, "location"),
          description = str(m, "description"),
          htmlLink    = str(m, "htmlLink"),
          status      = str(m, "status").getOrElse("confirmed")
        )
      case _ => None
    }

  /** A Google event time is `{ dateTime }` (timed) or `{ date }` (all-day).
   *  Returns the instant plus whether it was an all-day date. */
  private def parseTime(node: Option[Json]): Option[(Instant, Boolean)] =
    node match {
      case Some(Json.Obj(f)) =>
        val m = f.toMap
        str(m, "dateTime").flatMap(parseDateTime).map((_, false))
          .orElse(str(m, "date").flatMap(parseDate).map((_, true)))
      case _ => None
    }

  private def parseDateTime(s: String): Option[Instant] =
    scala.util.Try(OffsetDateTime.parse(s).toInstant).toOption
      .orElse(scala.util.Try(Instant.parse(s)).toOption)

  private def parseDate(s: String): Option[Instant] =
    scala.util.Try(LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant).toOption

  private def str(m: Map[String, Json], key: String): Option[String] =
    m.get(key).collect { case Json.Str(v) => v }

  private def getJson(accessToken: String, path: String): IO[AgentError, Json] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder()
        .uri(java.net.URI.create(ApiBase + path))
        .header("Authorization", s"Bearer $accessToken")
        .GET()
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 400)
        throw new RuntimeException(s"Calendar API ${resp.statusCode()}: ${resp.body()}")
      resp.body()
    }.mapError(t => AgentError.HttpFailed(s"Calendar: ${Option(t.getMessage).getOrElse("error")}", Some(t)))
      .flatMap(body => ZIO.fromEither(body.fromJson[Json]).mapError(msg => AgentError.DecodeFailed(msg)))

  private def postJson(accessToken: String, path: String, body: String): IO[AgentError, Json] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder()
        .uri(java.net.URI.create(ApiBase + path))
        .header("Authorization", s"Bearer $accessToken")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 400)
        throw new RuntimeException(s"Calendar API ${resp.statusCode()}: ${resp.body()}")
      resp.body()
    }.mapError(t => AgentError.HttpFailed(s"Calendar: ${Option(t.getMessage).getOrElse("error")}", Some(t)))
      .flatMap(b => ZIO.fromEither(b.fromJson[Json]).mapError(msg => AgentError.DecodeFailed(msg)))

  private def encode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)
}
