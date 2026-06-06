package dev.freskog.agent.person.api

import dev.freskog.agent.common.{Scope => PersonScope, _}
import dev.freskog.agent.common.JsonCodecs._
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.service.PersonService

import zio._
import zio.http._
import zio.json._

import java.time.Instant

object Routes {

  def make(service: PersonService): zio.http.Routes[Any, Response] =
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

      // --- scopes ---
      Method.GET / "scopes" -> handler { (_: Request) =>
        handleGet(service.listScopes)
      },
      Method.POST / "scopes" -> handler { (req: Request) =>
        handlePost[CreateScopeRequest, PersonScope](req)(service.createScope)
      },
      Method.POST / "scope-roles" -> handler { (req: Request) =>
        handlePost[CreateScopeRoleRequest, PersonScopeRole](req)(service.createScopeRole)
      },
      Method.GET / "scope-roles" -> handler { (req: Request) =>
        queryParam(req, "person") match {
          case None    => ZIO.succeed(errorToResponse(AgentError.BadRequest("person is required")))
          case Some(p) => handleGet(service.listScopeRoles(PersonId(p)))
        }
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
          scope = queryParam(req, "scope").map(ScopeId),
          status = queryParam(req, "status")
        ))
      },

      // --- memory: literal sub-paths first, dynamic :id second ---
      Method.POST / "memory" / "propose" -> handler { (req: Request) =>
        handlePost[ProposeMemoryRequest, MemoryItem](req)(service.proposeMemory)
      },
      Method.GET / "memory" -> handler { (req: Request) =>
        handleGet(service.listMemory(
          personId = queryParam(req, "person").map(PersonId),
          scopeId = queryParam(req, "scope").map(ScopeId)
        ))
      },
      Method.POST / "memory" / "supersede" -> handler { (req: Request) =>
        handlePost[SupersedeMemoryRequest, MemoryItem](req)(r => service.supersedeMemory(r.newId, r.oldId))
      },
      Method.POST / "memory" / "consolidate" -> handler { (req: Request) =>
        handlePost[ConsolidateRequest, List[MemoryItem]](req)(r => service.consolidateMemory(r.scopeId, r.since))
      },
      Method.GET / "memory" / "search" -> handler { (req: Request) =>
        val limit = queryParam(req, "limit").flatMap(_.toIntOption).getOrElse(10)
        parseInstantParam(req, "as_of").flatMap {
          case Left(err)  => ZIO.succeed(errorToResponse(err))
          case Right(asOf) =>
            handleGet(service.searchMemory(
              query = queryParam(req, "q").getOrElse(""),
              scopeId = queryParam(req, "scope").map(ScopeId),
              personId = queryParam(req, "person").map(PersonId),
              kind = queryParam(req, "kind"),
              asOf = asOf,
              limit = limit
            ))
        }
      },
      Method.GET / "memory" / "context" -> handler { (req: Request) =>
        handleGet(service.contextBundle(
          scopeId = queryParam(req, "scope").map(ScopeId),
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
            scopeId = queryParam(req, "scope").map(ScopeId),
            personId = queryParam(req, "person").map(PersonId),
            kind = kind,
            text = text
          ))
      },
      Method.POST / "memory" / string("id") / "accept" -> handler { (id: String, _: Request) =>
        handleGet(service.acceptMemory(MemoryId(id)))
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
          scopeId = queryParam(req, "scope").map(ScopeId),
          status = queryParam(req, "status")
        ))
      },

      // --- goals ---
      Method.POST / "goals" -> handler { (req: Request) =>
        handlePost[ProposeGoalRequest, Goal](req)(service.proposeGoal)
      },
      Method.GET / "goals" -> handler { (req: Request) =>
        handleGet(service.listGoals(
          owner = queryParam(req, "owner").map(PersonId),
          scope = queryParam(req, "scope").map(ScopeId),
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
              scopeId = queryParam(req, "scope").map(ScopeId),
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
              scopeId = queryParam(req, "scope").map(ScopeId),
              category = queryParam(req, "category"),
              since = since, until = until,
              limit = queryParam(req, "limit").flatMap(_.toIntOption).getOrElse(50)
            ))
        }
      }
    )

  // --- Combinators ---

  /** Run a read effect and serialise its result, mapping any AgentError to a
   *  status-coded JSON response. */
  private def handleGet[A: JsonEncoder](io: IO[AgentError, A]): UIO[Response] =
    io.fold(errorToResponse, a => Response.json(a.toJson))

  /** A get whose result may be `None`, translated to 404. */
  private def handleGetOption[A: JsonEncoder](io: IO[AgentError, Option[A]], targetType: String, id: String): UIO[Response] =
    io.fold(
      errorToResponse,
      {
        case Some(a) => Response.json(a.toJson)
        case None    => errorToResponse(AgentError.NotFound(targetType, id))
      }
    )

  /** Decode the request body and run the effect; 400 on body errors, structured
   *  status on service errors. 201 Created on success. */
  private def handlePost[A: JsonDecoder, B: JsonEncoder](req: Request)(svc: A => IO[AgentError, B]): UIO[Response] =
    parseBody[A](req).flatMap {
      case Left(err) => ZIO.succeed(errorToResponse(err))
      case Right(a)  => svc(a).fold(errorToResponse, b => Response.json(b.toJson).status(Status.Created))
    }

  /** Like `handlePost` but the body is optional: an empty body still succeeds. */
  private def handlePostOptional[A: JsonDecoder, B: JsonEncoder](req: Request)(svc: Option[A] => IO[AgentError, B]): UIO[Response] =
    req.body.asString.foldZIO(
      _ => svc(None).fold(errorToResponse, b => Response.json(b.toJson)),
      raw =>
        if (raw.trim.isEmpty || raw.trim == "{}") svc(None).fold(errorToResponse, b => Response.json(b.toJson))
        else
          ZIO.fromEither(raw.fromJson[A])
            .mapError(msg => AgentError.BadRequest(s"invalid JSON body: $msg"))
            .foldZIO(
              e => ZIO.succeed(errorToResponse(e)),
              a => svc(Some(a)).fold(errorToResponse, b => Response.json(b.toJson))
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

  /** Map an `AgentError` to an HTTP response with a structured JSON body. */
  private def errorToResponse(e: AgentError): Response = {
    val status = e match {
      case _: AgentError.NotFound     => Status.NotFound
      case _: AgentError.BadRequest   => Status.BadRequest
      case _: AgentError.Validation   => Status.BadRequest
      case _: AgentError.DecodeFailed => Status.BadRequest
      case _: AgentError.Persistence  => Status.InternalServerError
      case _: AgentError.HttpFailed   => Status.BadGateway
      case _: AgentError.HttpBadStatus => Status.BadGateway
      case _: AgentError.Bug          => Status.InternalServerError
    }
    Response.json(e.toJson).status(status)
  }
}
