package dev.freskog.agent.person.api

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.gmail.GmailConfig
import dev.freskog.agent.person.service.PersonService

import zio._
import zio.http._
import zio.json._
import zio.json.ast.Json
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.time.Instant

object Routes {

  def make(service: PersonService, approvalHub: Hub[ApprovalEvent]): zio.http.Routes[Any, Response] =
    zio.http.Routes(

      Method.GET / "health" -> handler { (_: Request) =>
        Response.json("""{"status":"ok"}""")
      },

      // --- persons ---
      Method.GET / "persons" -> handler { (_: Request) =>
        handleGet(service.listPersons)
      },
      Method.POST / "persons" -> handler { (req: Request) =>
        handlePost[CreatePersonRequest, Person](req)(service.createPerson)
      },
      Method.POST / "persons" / string("id") -> handler { (id: String, req: Request) =>
        handlePost[UpdatePersonRequest, Person](req)(r => service.updatePerson(PersonId(id), r))
      },

      // --- household graph: entities ---
      Method.POST / "entities" / "propose" -> handler { (req: Request) =>
        handlePost[ProposeEntityRequest, Entity](req)(service.proposeEntity)
      },
      Method.GET / "entities" -> handler { (req: Request) =>
        queryParam(req, "name") match {
          case Some(n) => handleGet(service.resolveEntities(n))
          case None    =>
            handleGet(service.listEntities(
              kind = queryParam(req, "kind"),
              status = queryParam(req, "status")
            ))
        }
      },
      Method.POST / "entities" / "supersede" -> handler { (req: Request) =>
        handlePost[SupersedeEntityRequest, Entity](req)(r => service.supersedeEntity(r.newId, r.oldId))
      },
      Method.POST / "entities" / string("id") / "reject" -> handler { (id: String, req: Request) =>
        handlePostOptional[RejectMemoryRequest, Entity](req) { reqOpt =>
          service.rejectEntity(EntityId(id), reqOpt.flatMap(_.reason))
        }
      },

      // --- household graph: relationships ---
      Method.POST / "relationships" / "propose" -> handler { (req: Request) =>
        handlePost[ProposeRelationshipRequest, Relationship](req)(service.proposeRelationship)
      },
      Method.GET / "relationships" -> handler { (req: Request) =>
        parseInstantParam(req, "as_of").flatMap {
          case Left(err)   => ZIO.succeed(errorToResponse(err))
          case Right(asOf) =>
            handleGet(service.listRelationships(
              fromId = queryParam(req, "from"),
              toId = queryParam(req, "to"),
              relType = queryParam(req, "type"),
              status = queryParam(req, "status"),
              asOf = asOf
            ))
        }
      },
      Method.POST / "relationships" / "supersede" -> handler { (req: Request) =>
        handlePost[SupersedeRelationshipRequest, Relationship](req)(r => service.supersedeRelationship(r.newId, r.oldId))
      },
      Method.POST / "relationships" / string("id") / "reject" -> handler { (id: String, req: Request) =>
        handlePostOptional[RejectMemoryRequest, Relationship](req) { reqOpt =>
          service.rejectRelationship(RelationshipId(id), reqOpt.flatMap(_.reason))
        }
      },

      // --- household graph: combined snapshot ---
      Method.GET / "household" -> handler { (_: Request) =>
        handleGet(service.household)
      },

      // --- channels & messages (mycroft) ---
      Method.POST / "channels" -> handler { (req: Request) =>
        handlePost[CreateChannelRequest, ChannelWithMembers](req)(service.createChannel)
      },
      Method.GET / "channels" -> handler { (_: Request) =>
        handleGet(service.listChannels)
      },
      Method.GET / "channels" / string("id") -> handler { (id: String, _: Request) =>
        handleGetOption(service.getChannel(ChannelId(id)), "channel", id)
      },
      Method.POST / "channels" / string("id") / "members" -> handler { (id: String, req: Request) =>
        handlePost[AddMemberRequest, ChannelWithMembers](req)(r => service.addChannelMember(ChannelId(id), r.personId))
      },
      Method.POST / "messages" -> handler { (req: Request) =>
        handlePost[AppendMessageRequest, Message](req)(service.appendMessage)
      },
      Method.GET / "messages" -> handler { (req: Request) =>
        queryParam(req, "channel") match {
          case None => ZIO.succeed(errorToResponse(AgentError.BadRequest("channel is required")))
          case Some(ch) =>
            parseInstantParam(req, "since").flatMap {
              case Left(err)    => ZIO.succeed(errorToResponse(err))
              case Right(since) =>
                handleGet(service.listMessages(
                  ChannelId(ch), since,
                  queryParam(req, "limit").flatMap(_.toIntOption).getOrElse(50)
                ))
            }
        }
      },

      // --- commitments ---
      Method.POST / "commitments" / "propose" -> handler { (req: Request) =>
        handlePost[ProposeCommitmentRequest, Commitment](req)(service.proposeCommitment)
      },
      Method.GET / "commitments" -> handler { (req: Request) =>
        handleGet(service.listCommitments(
          owner = queryParam(req, "owner").map(PersonId),
          status = queryParam(req, "status")
        ))
      },
      Method.POST / "commitments" / string("id") / "status" -> handler { (id: String, req: Request) =>
        handlePost[UpdateCommitmentStatusRequest, Commitment](req)(r => service.updateCommitmentStatus(CommitmentId(id), r.status, r.reason))
      },

      // --- memory: literal sub-paths first, dynamic :id second ---
      Method.POST / "memory" / "propose" -> handler { (req: Request) =>
        handlePost[ProposeMemoryRequest, MemoryItem](req)(service.proposeMemory)
      },
      Method.GET / "memory" -> handler { (req: Request) =>
        handleGet(service.listMemory(
          personId = queryParam(req, "person").map(PersonId),
          status   = queryParam(req, "status"),
          kind     = queryParam(req, "kind")
        ))
      },
      Method.GET / "memory" / "profile" -> handler { (req: Request) =>
        handleGet(service.profileFacts(
          limit = queryParam(req, "limit").flatMap(_.toIntOption).getOrElse(50)
        ))
      },
      Method.POST / "memory" / "supersede" -> handler { (req: Request) =>
        handlePost[SupersedeMemoryRequest, MemoryItem](req)(r => service.supersedeMemory(r.newId, r.oldId))
      },
      Method.POST / "memory" / "consolidate" -> handler { (req: Request) =>
        handlePostOptional[ConsolidateRequest, List[MemoryItem]](req)(r => service.consolidateMemory(r.flatMap(_.since)))
      },
      Method.GET / "memory" / "search" -> handler { (req: Request) =>
        val limit = queryParam(req, "limit").flatMap(_.toIntOption).getOrElse(10)
        parseInstantParam(req, "as_of").flatMap {
          case Left(err)  => ZIO.succeed(errorToResponse(err))
          case Right(asOf) =>
            handleGet(service.searchMemory(
              query = queryParam(req, "q").getOrElse(""),
              personId = queryParam(req, "person").map(PersonId),
              kind = queryParam(req, "kind"),
              asOf = asOf,
              limit = limit
            ))
        }
      },
      Method.GET / "memory" / "context" -> handler { (req: Request) =>
        handleGet(service.contextBundle(
          personId = queryParam(req, "person").map(PersonId),
          factLimit = queryParam(req, "fact_limit").flatMap(_.toIntOption).getOrElse(10),
          eventLimit = queryParam(req, "event_limit").flatMap(_.toIntOption).getOrElse(10)
        ))
      },
      Method.GET / "memory" / "conflicts" -> handler { (req: Request) =>
        val kind = queryParam(req, "kind").getOrElse("")
        val text = queryParam(req, "text").getOrElse("")
        if (kind.isEmpty || text.isEmpty)
          ZIO.succeed(errorToResponse(AgentError.BadRequest("kind and text are required")))
        else
          handleGet(service.findConflicts(
            personId = queryParam(req, "person").map(PersonId),
            kind = kind,
            text = text
          ))
      },
      Method.POST / "memory" / string("id") / "reject" -> handler { (id: String, req: Request) =>
        handlePostOptional[RejectMemoryRequest, MemoryItem](req) { reqOpt =>
          service.rejectMemory(MemoryId(id), reqOpt.flatMap(_.reason))
        }
      },
      Method.POST / "memory" / string("id") / "archive" -> handler { (id: String, _: Request) =>
        handleGet(service.archiveMemory(MemoryId(id)))
      },

      // --- approvals ---
      Method.POST / "approvals" / "request" -> handler { (req: Request) =>
        handlePost[RequestApprovalRequest, Approval](req)(service.requestApproval)
      },
      Method.GET / "approvals" -> handler { (req: Request) =>
        handleGet(service.listApprovals(
          status = queryParam(req, "status")
        ))
      },
      // Approval lifecycle event stream. Edges subscribe and hold the connection;
      // person-service streams events down it (clients render `requested`, mycroft
      // acts on `executed`). Optional `?person=<id>` filters to approvals that
      // person must decide (plus untargeted ones). The core never dials out.
      Method.GET / "approvals" / "stream" -> handler { (req: Request) =>
        val person   = queryParam(req, "person")
        val greeting = ZStream.succeed(": connected\n\n")
        val events   = ZStream.fromHub(approvalHub)
          .filter(e => person.isEmpty || e.approval.requiredPersonId.isEmpty || e.approval.requiredPersonId.map(_.value) == person)
          .map(e => s"event: ${e.kind}\ndata: ${e.toJson}\n\n")
        val bytes = (greeting ++ events).mapConcatChunk(s => Chunk.fromArray(s.getBytes(StandardCharsets.UTF_8)))
        ZIO.succeed(
          Response(body = Body.fromStreamChunked(bytes)).addHeader("Content-Type", "text/event-stream")
        )
      },
      Method.GET / "approvals" / string("id") -> handler { (id: String, _: Request) =>
        handleGetOption(service.getApproval(ApprovalId(id)), "approval", id)
      },
      // NOTE: the decision endpoint (POST /approvals/:id/decide) is deliberately
      // NOT here. It lives in `decideRoutes`, served on a separate private
      // interface the agent's network can't reach — see Main. This is the
      // structural guarantee that a compromised agent cannot approve its own
      // proposals.

      // --- goals ---
      // NOTE: there is no POST /goals. Goal creation is gated — the agent requests
      // a `goal.create` approval; person-service creates the goal only on human
      // approval (the executor calls proposeGoal). Status/evidence below stay direct.
      Method.GET / "goals" -> handler { (req: Request) =>
        handleGet(service.listGoals(
          owner = queryParam(req, "owner").map(PersonId),
          status = queryParam(req, "status")
        ))
      },
      Method.GET / "goals" / string("id") -> handler { (id: String, _: Request) =>
        handleGetOption(service.getGoal(GoalId(id)), "goal", id)
      },
      Method.POST / "goals" / string("id") / "status" -> handler { (id: String, req: Request) =>
        handlePost[UpdateGoalStatusRequest, Goal](req)(r => service.updateGoalStatus(GoalId(id), r))
      },
      Method.POST / "goals" / string("id") / "evidence" -> handler { (id: String, req: Request) =>
        handlePost[AppendGoalEvidenceRequest, GoalEvidence](req)(r => service.appendGoalEvidence(GoalId(id), r))
      },

      // --- events ---
      Method.POST / "events" -> handler { (req: Request) =>
        handlePost[LogEventRequest, AuditEvent](req)(service.logEvent)
      },
      Method.GET / "events" / "search" -> handler { (req: Request) =>
        parseInstantParam(req, "since").flatMap {
          case Left(err)   => ZIO.succeed(errorToResponse(err))
          case Right(since) =>
            handleGet(service.searchEvents(
              query = queryParam(req, "q").getOrElse(""),
              category = queryParam(req, "category"),
              since = since,
              limit = queryParam(req, "limit").flatMap(_.toIntOption).getOrElse(10)
            ))
        }
      },
      Method.GET / "events" -> handler { (req: Request) =>
        val r = for {
          since <- parseInstantParam(req, "since")
          until <- parseInstantParam(req, "until")
        } yield (since, until)
        r.flatMap {
          case (Left(err), _)               => ZIO.succeed(errorToResponse(err))
          case (_, Left(err))               => ZIO.succeed(errorToResponse(err))
          case (Right(since), Right(until)) =>
            handleGet(service.listEvents(
              category = queryParam(req, "category"),
              since = since, until = until,
              limit = queryParam(req, "limit").flatMap(_.toIntOption).getOrElse(50)
            ))
        }
      },

      // --- gmail / inbox ---
      Method.GET / "gmail" / "auth-url" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None    => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) => handleGet(service.gmailAuthUrl(PersonId(o)))
        }
      },
      Method.POST / "gmail" / "oauth" / "exchange" -> handler { (req: Request) =>
        handlePost[GmailOAuthExchangeRequest, GmailCredentialSummary](req)(service.gmailOAuthExchange)
      },
      // Consent URL for the dedicated send-only sender account (briefings send as it).
      Method.GET / "gmail" / "sender-auth-url" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None    => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) => handleGet(service.senderAuthUrl(PersonId(o)))
        }
      },
      // Operator-facing OAuth redirect target. Google sends the human's browser
      // here after consent; person-service completes the exchange itself, so the
      // agent is never involved (and no localhost socket is bound in the sandbox).
      // `state` carries the owner (set by gmailAuthUrl); `redirectUri` in the
      // request is unused — the exchange uses the configured GMAIL_REDIRECT_URI.
      Method.GET / "gmail" / "oauth" / "callback" -> handler { (req: Request) =>
        (queryParam(req, "error"), queryParam(req, "code"), queryParam(req, "state")) match {
          case (Some(err), _, _) =>
            ZIO.succeed(htmlResponse(oauthPage("Gmail authorization failed", err), Status.BadRequest))
          // The dedicated sender consent carries `state = SenderStatePrefix + ownerId`.
          case (None, Some(code), Some(st)) if st.startsWith(GmailConfig.SenderStatePrefix) =>
            val owner = st.drop(GmailConfig.SenderStatePrefix.length)
            service.senderOAuthExchange(PersonId(owner), code).foldZIO(
              e => failed(e).map(_ => htmlResponse(oauthPage("Sender authorization failed", e.message), statusOf(e))),
              summary =>
                ZIO.succeed(htmlResponse(oauthPage("Sender connected", s"Briefings for ${summary.ownerPersonId.value} will send as ${summary.accountEmail}. You can close this tab.")))
            )
          case (None, Some(code), Some(owner)) =>
            service
              .gmailOAuthExchange(GmailOAuthExchangeRequest(PersonId(owner), code, ""))
              .foldZIO(
                e => failed(e).map(_ => htmlResponse(oauthPage("Gmail authorization failed", e.message), statusOf(e))),
                summary =>
                  ZIO.succeed(htmlResponse(oauthPage("Gmail connected", s"Connected ${summary.accountEmail} for ${summary.ownerPersonId.value}. You can close this tab.")))
              )
          case _ =>
            ZIO.succeed(htmlResponse(oauthPage("Gmail authorization failed", "missing code or state"), Status.BadRequest))
        }
      },
      Method.POST / "gmail" / "sync" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) =>
            parseInstantParam(req, "since").flatMap {
              case Left(err)    => ZIO.succeed(errorToResponse(err))
              case Right(since) => handleGet(service.gmailSync(PersonId(o), since))
            }
        }
      },
      Method.GET / "inbox" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) =>
            handleGet(service.listInbox(
              ownerPersonId = PersonId(o),
              status = queryParam(req, "status"),
              limit = queryParam(req, "limit").flatMap(_.toIntOption).getOrElse(20),
              oldestFirst = queryParam(req, "order").exists(_.equalsIgnoreCase("asc"))
            ))
        }
      },
      Method.GET / "inbox" / string("id") -> handler { (id: String, _: Request) =>
        handleGetOption(service.getInbox(InboxMessageId(id)), "inbox message", id)
      },
      Method.GET / "inbox" / string("id") / "attachments" / string("attachmentId") -> handler {
        (id: String, attachmentId: String, _: Request) =>
          handleGet(service.downloadAttachment(InboxMessageId(id), attachmentId))
      },
      // Raw attachment bytes (base64-decoded server-side) so the thin `person`
      // script can `curl -o` straight to a file — no base64 handling in bash.
      Method.GET / "inbox" / string("id") / "attachments" / string("attachmentId") / "raw" -> handler {
        (id: String, attachmentId: String, _: Request) =>
          service.downloadAttachment(InboxMessageId(id), attachmentId).foldZIO(
            failed,
            dl => ZIO.attempt(java.util.Base64.getDecoder.decode(dl.dataBase64)).foldZIO(
              t => failed(AgentError.Bug(s"decoding attachment $attachmentId: ${Option(t.getMessage).getOrElse("error")}")),
              bytes =>
                ZIO.succeed(
                  Response(body = Body.fromArray(bytes))
                    .addHeader(Header.ContentType(MediaType.forContentType(dl.mimeType).getOrElse(MediaType.application.`octet-stream`)))
                    .addHeader("Content-Disposition", s"""attachment; filename="${dl.filename.replaceAll("[\"\\r\\n]", "_")}"""")
                )
            )
          )
      },
      Method.POST / "inbox" / string("id") / "skip" -> handler { (id: String, _: Request) =>
        handleGet(service.skipInbox(InboxMessageId(id)))
      },
      Method.POST / "inbox" / string("id") / "mark-triaged" -> handler { (id: String, req: Request) =>
        handlePostOptional[MarkInboxTriagedRequest, InboxMessage](req) { bodyOpt =>
          service.markInboxTriaged(InboxMessageId(id), bodyOpt.flatMap(_.sourceEventId))
        }
      },

      // --- calendar (read-only) ---
      Method.GET / "calendar" / "agenda" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) =>
            (parseInstantParam(req, "from") <*> parseInstantParam(req, "to")).flatMap {
              case (Left(err), _) => ZIO.succeed(errorToResponse(err))
              case (_, Left(err)) => ZIO.succeed(errorToResponse(err))
              case (Right(fromOpt), Right(toOpt)) =>
                val days = queryParam(req, "days").flatMap(_.toIntOption).getOrElse(14).max(1)
                Clock.instant.flatMap { now =>
                  val from = fromOpt.getOrElse(now)
                  val to   = toOpt.getOrElse(from.plus(java.time.Duration.ofDays(days.toLong)))
                  handleGet(service.calendarAgenda(PersonId(o), from, to))
                }
            }
        }
      },

      // List the owner's calendars (read-only; the agent can see, not pick).
      Method.GET / "calendar" / "calendars" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None    => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) => handleGet(service.listCalendars(PersonId(o)))
        }
      },

      // Direct calendar event creation ([M]-marked, idempotent — no approval).
      Method.POST / "calendar" / "events" -> handler { (req: Request) =>
        handlePost[CalendarCreateEventRequest, CalendarEvent](req)(service.createCalendarEventDirect)
      },

      // --- projection sync (poller-driven infrastructure) ---
      Method.POST / "tasks" / "sync" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None    => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) => handleGet(service.syncTasks(PersonId(o)))
        }
      },
      Method.POST / "calendar" / "sync" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None    => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) => handleGet(service.syncCalendar(PersonId(o)))
        }
      },

      // --- daily briefing (agent submits; person-service delivers) ---
      Method.POST / "briefing" -> handler { (req: Request) =>
        handlePost[SubmitBriefingRequest, Briefing](req)(service.submitBriefing)
      },
      Method.POST / "briefing" / "run" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None    => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) => handleGet(service.runDailyBriefing(PersonId(o)).as(Map("status" -> "ok")))
        }
      },
      Method.POST / "briefing" / "deliver" -> handler { (req: Request) =>
        queryParam(req, "owner") match {
          case None    => ZIO.succeed(errorToResponse(AgentError.BadRequest("owner is required")))
          case Some(o) => handleGet(service.deliverPending(PersonId(o)).map(n => Map("delivered" -> n)))
        }
      }
    )

  /** The decision plane, served on person-service's PRIVATE interface only (a
   *  network the agent can't route to). Issuing a one-time code is here so the
   *  agent can never obtain one; `decide` requires that code. Approving executes
   *  the action server-side and emits an `executed` event on the (public) stream. */
  def decideRoutes(service: PersonService): zio.http.Routes[Any, Response] =
    zio.http.Routes(
      // Mint a one-time decision code for a pending approval (returns plaintext).
      Method.GET / "approvals" / string("id") / "code" -> handler { (id: String, _: Request) =>
        service.issueDecisionCode(ApprovalId(id)).foldZIO(
          failed,
          code => ZIO.succeed(Response.json(Json.Obj("code" -> Json.Str(code)).toJson))
        )
      },
      Method.POST / "approvals" / string("id") / "decide" -> handler { (id: String, req: Request) =>
        handlePost[DecideApprovalRequest, Approval](req)(r => service.decideApproval(ApprovalId(id), r))
      }
    )

  // --- Combinators ---

  /** Run a read effect and serialise its result, mapping any AgentError to a
   *  status-coded JSON response. */
  private def handleGet[A: JsonEncoder](io: IO[AgentError, A]): UIO[Response] =
    io.foldZIO(failed, a => ZIO.succeed(Response.json(a.toJson)))

  /** A get whose result may be `None`, translated to 404. */
  private def handleGetOption[A: JsonEncoder](io: IO[AgentError, Option[A]], targetType: String, id: String): UIO[Response] =
    io.foldZIO(
      failed,
      {
        case Some(a) => ZIO.succeed(Response.json(a.toJson))
        case None    => failed(AgentError.NotFound(targetType, id))
      }
    )

  /** Decode the request body and run the effect; 400 on body errors, structured
   *  status on service errors. 201 Created on success. */
  private def handlePost[A: JsonDecoder, B: JsonEncoder](req: Request)(svc: A => IO[AgentError, B]): UIO[Response] =
    parseBody[A](req).flatMap {
      case Left(err) => failed(err)
      case Right(a)  => svc(a).foldZIO(failed, b => ZIO.succeed(Response.json(b.toJson).status(Status.Created)))
    }

  /** Like `handlePost` but the body is optional: an empty body still succeeds. */
  private def handlePostOptional[A: JsonDecoder, B: JsonEncoder](req: Request)(svc: Option[A] => IO[AgentError, B]): UIO[Response] =
    req.body.asString.foldZIO(
      _ => svc(None).foldZIO(failed, b => ZIO.succeed(Response.json(b.toJson))),
      raw =>
        if (raw.trim.isEmpty || raw.trim == "{}") svc(None).foldZIO(failed, b => ZIO.succeed(Response.json(b.toJson)))
        else
          ZIO.fromEither(raw.fromJson[A])
            .mapError(msg => AgentError.BadRequest(s"invalid JSON body: $msg"))
            .foldZIO(
              failed,
              a => svc(Some(a)).foldZIO(failed, b => ZIO.succeed(Response.json(b.toJson)))
            )
    )

  /** Read the body as `A`, returning a `BadRequest` on decode failure. */
  private def parseBody[A: JsonDecoder](req: Request): UIO[Either[AgentError, A]] =
    req.body.asString.mapError(t => AgentError.BadRequest(s"reading body: ${Option(t.getMessage).getOrElse("io error")}"))
      .flatMap(raw =>
        ZIO
          .fromEither(raw.fromJson[A])
          .mapError(msg => AgentError.BadRequest(s"invalid JSON body: $msg"))
      )
      .either

  /** Optional ISO-8601 query parameter. Returns `Right(None)` when absent. */
  private def parseInstantParam(req: Request, name: String): UIO[Either[AgentError, Option[Instant]]] =
    ZIO.succeed(queryParam(req, name) match {
      case None    => Right(None)
      case Some(s) =>
        try Right(Some(Instant.parse(s)))
        catch { case _: Throwable => Left(AgentError.BadRequest(s"invalid timestamp for $name=$s")) }
    })

  private def queryParam(req: Request, name: String): Option[String] =
    req.url.queryParams.getAll(name).headOption

  private def statusOf(e: AgentError): Status = e match {
    case _: AgentError.NotFound      => Status.NotFound
    case _: AgentError.BadRequest    => Status.BadRequest
    case _: AgentError.Validation    => Status.BadRequest
    case _: AgentError.DecodeFailed  => Status.BadRequest
    case _: AgentError.Persistence   => Status.InternalServerError
    case _: AgentError.HttpFailed    => Status.BadGateway
    case _: AgentError.HttpBadStatus => Status.BadGateway
    case _: AgentError.Bug           => Status.InternalServerError
  }

  /** Map an `AgentError` to an HTTP response with a structured JSON body. */
  private def errorToResponse(e: AgentError): Response =
    Response.json(e.toJson).status(statusOf(e))

  /** A minimal text/html response for the operator-facing OAuth callback (the one
   *  place person-service talks to a human's browser rather than to JSON clients). */
  private def htmlResponse(body: String, status: Status = Status.Ok): Response =
    Response(status = status, body = Body.fromString(body, StandardCharsets.UTF_8))
      .addHeader(Header.ContentType(MediaType.text.html))

  private def oauthPage(heading: String, detail: String): String =
    s"<!doctype html><html><head><meta charset=\"utf-8\"><title>$heading</title></head>" +
      s"<body style=\"font-family:sans-serif;max-width:32rem;margin:4rem auto\">" +
      s"<h2>$heading</h2><p>$detail</p></body></html>"

  /** Log an error (5xx at error level so it surfaces in container logs, 4xx at
   *  info) and produce the corresponding response. Use this on the error branch
   *  of any handler that runs a service effect, so failures are never silent. */
  private def failed(e: AgentError): UIO[Response] = {
    val label = e.getClass.getSimpleName
    val log   =
      if (statusOf(e).code >= 500) ZIO.logError(s"$label: ${e.message}")
      else ZIO.logInfo(s"$label: ${e.message}")
    log.as(errorToResponse(e))
  }
}
