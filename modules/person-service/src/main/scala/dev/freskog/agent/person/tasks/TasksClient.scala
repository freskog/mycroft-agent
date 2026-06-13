package dev.freskog.agent.person.tasks

import dev.freskog.agent.common.AgentError
import dev.freskog.agent.person.gmail.GmailConfig

import zio._
import zio.json._
import zio.json.ast.Json

import java.net.URLEncoder
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}

/** Minimal Google Tasks client — the projection target for commitments (todos).
 *  Server-side only (person-service holds the credential); the agent never reaches
 *  it. Uses the same Google OAuth access token as Gmail/Calendar. */
object TasksClient {

  val ApiBase: String = GmailConfig.TasksApiBase

  /** A Google Task, reduced to the fields we project/sync. `due` is date-only in
   *  Google (any time component is ignored), so we carry the date as a UTC instant
   *  at midnight. `completed` collapses the `status` field; `deleted`/`hidden` mark
   *  tasks the user removed or checked off (hidden) on their device. */
  final case class GTask(
    id: String,
    title: String,
    notes: Option[String],
    due: Option[Instant],
    completed: Boolean,
    deleted: Boolean,
    updated: Option[Instant]
  )

  private val client: JHttpClient = JHttpClient.newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(30))
    .build()

  /** The owner's task lists as `(id, title)`. The default list can also be addressed
   *  as the literal id `@default`, but listing lets us pick/track a stable one. */
  def listTaskLists(accessToken: String): IO[AgentError, List[(String, String)]] =
    getJson(accessToken, "/users/@me/lists").map(parseTaskLists)

  /** Tasks on `taskListId`. `showCompleted`+`showHidden` so checked-off items are
   *  visible for sync-back; `updatedMin` (optional) limits to recent changes. */
  def listTasks(
    accessToken: String,
    taskListId: String,
    updatedMin: Option[Instant] = None,
    showCompleted: Boolean = true
  ): IO[AgentError, List[GTask]] = {
    val params = List(
      s"showCompleted=$showCompleted",
      s"showHidden=$showCompleted",
      "maxResults=100"
    ) ++ updatedMin.map(m => s"updatedMin=${encode(m.toString)}")
    getJson(accessToken, s"/lists/${encode(taskListId)}/tasks?${params.mkString("&")}").map(parseTasks)
  }

  def insertTask(
    accessToken: String,
    taskListId: String,
    title: String,
    notes: Option[String],
    due: Option[Instant]
  ): IO[AgentError, GTask] =
    send(accessToken, "POST", s"/lists/${encode(taskListId)}/tasks",
         Some(buildTaskBody(title, notes, due, None).toJson))
      .flatMap(parseOne)

  /** Patch a task's fields. Any `Some` is written; `None` leaves it unchanged. */
  def patchTask(
    accessToken: String,
    taskListId: String,
    taskId: String,
    title: Option[String] = None,
    notes: Option[String] = None,
    due: Option[Instant] = None,
    status: Option[String] = None
  ): IO[AgentError, GTask] =
    send(accessToken, "PATCH", s"/lists/${encode(taskListId)}/tasks/${encode(taskId)}",
         Some(buildPatchBody(title, notes, due, status).toJson))
      .flatMap(parseOne)

  /** Mark a task completed (Google also hides it from the default view). */
  def completeTask(accessToken: String, taskListId: String, taskId: String): IO[AgentError, GTask] =
    patchTask(accessToken, taskListId, taskId, status = Some("completed"))

  def deleteTask(accessToken: String, taskListId: String, taskId: String): IO[AgentError, Unit] =
    send(accessToken, "DELETE", s"/lists/${encode(taskListId)}/tasks/${encode(taskId)}", None).unit

  // --- pure body builders (unit-tested) ---

  /** A full task resource for insert. `due` is emitted as an RFC3339 timestamp
   *  (Google keeps only the date). */
  def buildTaskBody(title: String, notes: Option[String], due: Option[Instant], status: Option[String]): Json = {
    val base = List[(String, Json)]("title" -> Json.Str(title))
    Json.Obj(Chunk.fromIterable(base ++ optionalFields(notes, due, status)))
  }

  /** A patch body — only the fields we intend to change. */
  def buildPatchBody(title: Option[String], notes: Option[String], due: Option[Instant], status: Option[String]): Json =
    Json.Obj(Chunk.fromIterable(
      title.map(t => "title" -> Json.Str(t)).toList ++ optionalFields(notes, due, status)
    ))

  private def optionalFields(notes: Option[String], due: Option[Instant], status: Option[String]): List[(String, Json)] =
    notes.map(n => "notes" -> Json.Str(n)).toList ++
      due.map(d => "due" -> Json.Str(dueString(d))).toList ++
      status.map(s => "status" -> Json.Str(s)).toList

  /** Google Tasks `due` is date-only; send midnight-UTC RFC3339 for the date. */
  private def dueString(i: Instant): String =
    i.atZone(ZoneOffset.UTC).toLocalDate.atStartOfDay(ZoneOffset.UTC).toInstant.toString

  // --- parsing ---

  private def parseTaskLists(json: Json): List[(String, String)] =
    json match {
      case Json.Obj(fields) =>
        fields.collectFirst { case ("items", Json.Arr(xs)) => xs }.getOrElse(Chunk.empty)
          .toList.flatMap {
            case Json.Obj(f) =>
              val m = f.toMap
              str(m, "id").map(id => id -> str(m, "title").getOrElse(id))
            case _ => None
          }
      case _ => Nil
    }

  private def parseTasks(json: Json): List[GTask] =
    json match {
      case Json.Obj(fields) =>
        fields.collectFirst { case ("items", Json.Arr(xs)) => xs }.getOrElse(Chunk.empty)
          .toList.flatMap(parseTask)
      case _ => Nil
    }

  private def parseTask(json: Json): Option[GTask] =
    json match {
      case Json.Obj(fields) =>
        val m = fields.toMap
        str(m, "id").map { id =>
          GTask(
            id        = id,
            title     = str(m, "title").getOrElse(""),
            notes     = str(m, "notes"),
            due       = str(m, "due").flatMap(parseInstant),
            completed = str(m, "status").contains("completed"),
            deleted   = m.get("deleted").contains(Json.Bool(true)),
            updated   = str(m, "updated").flatMap(parseInstant)
          )
        }
      case _ => None
    }

  private def parseInstant(s: String): Option[Instant] =
    scala.util.Try(OffsetDateTime.parse(s).toInstant).toOption
      .orElse(scala.util.Try(Instant.parse(s)).toOption)
      .orElse(scala.util.Try(LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant).toOption)

  private def str(m: Map[String, Json], key: String): Option[String] =
    m.get(key).collect { case Json.Str(v) => v }

  private def parseOne(json: Json): IO[AgentError, GTask] =
    ZIO.fromOption(parseTask(json)).orElseFail(AgentError.DecodeFailed("tasks: unparseable task in response"))

  // --- HTTP ---

  private def getJson(accessToken: String, path: String): IO[AgentError, Json] =
    send(accessToken, "GET", path, None)

  /** One request returning parsed JSON (or `Json.Null` for an empty body, e.g.
   *  DELETE returns 204). Non-2xx bodies surface verbatim via `HttpFailed`. */
  private def send(accessToken: String, method: String, path: String, body: Option[String]): IO[AgentError, Json] =
    ZIO.attemptBlocking {
      val builder = HttpRequest.newBuilder()
        .uri(java.net.URI.create(ApiBase + path))
        .header("Authorization", s"Bearer $accessToken")
      val publisher = body.map(HttpRequest.BodyPublishers.ofString).getOrElse(HttpRequest.BodyPublishers.noBody())
      if (body.isDefined) builder.header("Content-Type", "application/json")
      val req  = builder.method(method, publisher).build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 400)
        throw new RuntimeException(s"Tasks API ${resp.statusCode()}: ${resp.body()}")
      resp.body()
    }.mapError(t => AgentError.HttpFailed(s"Tasks: ${Option(t.getMessage).getOrElse("error")}", Some(t)))
      .flatMap { b =>
        if (b == null || b.trim.isEmpty) ZIO.succeed(Json.Null)
        else ZIO.fromEither(b.fromJson[Json]).mapError(msg => AgentError.DecodeFailed(msg))
      }

  private def encode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)
}
