package dev.freskog.agent.person.api

import dev.freskog.agent.common.{ErrorResponse, Scope => _, _}
import dev.freskog.agent.common.JsonCodecs._
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.service.PersonService

import zio._
import zio.http._
import zio.json._

object Routes {

  def make(service: PersonService): zio.http.Routes[Any, Nothing] =
    zio.http.Routes(
      Method.GET / "health" -> handler { (_: Request) =>
        Response.json("""{"status":"ok"}""")
      },

      Method.GET / "persons" -> handler { (_: Request) =>
        service.listPersons
          .map(ps => Response.json(ps.toJson))
          .orElse(ZIO.succeed(Response.status(Status.InternalServerError)))
      },

      Method.POST / "persons" -> handler { (req: Request) =>
        parseBody[CreatePersonRequest](req).flatMap {
          case None => ZIO.succeed(Response.json("""{"error":"invalid body"}""").status(Status.BadRequest))
          case Some(body) =>
            service.createPerson(body)
              .map(p => Response.json(p.toJson).status(Status.Created))
              .catchAll(e => ZIO.succeed(errorResponse(e.getMessage)))
        }
      },

      Method.GET / "scopes" -> handler { (_: Request) =>
        service.listScopes
          .map(ss => Response.json(ss.toJson))
          .orElse(ZIO.succeed(Response.status(Status.InternalServerError)))
      },

      Method.POST / "scopes" -> handler { (req: Request) =>
        parseBody[CreateScopeRequest](req).flatMap {
          case None => ZIO.succeed(Response.json("""{"error":"invalid body"}""").status(Status.BadRequest))
          case Some(body) =>
            service.createScope(body)
              .map(s => Response.json(s.toJson).status(Status.Created))
              .catchAll(e => ZIO.succeed(errorResponse(e.getMessage)))
        }
      },

      Method.POST / "scope-roles" -> handler { (req: Request) =>
        parseBody[CreateScopeRoleRequest](req).flatMap {
          case None => ZIO.succeed(Response.json("""{"error":"invalid body"}""").status(Status.BadRequest))
          case Some(body) =>
            service.createScopeRole(body)
              .map(r => Response.json(r.toJson).status(Status.Created))
              .catchAll(e => ZIO.succeed(errorResponse(e.getMessage)))
        }
      },

      Method.POST / "commitments" / "propose" -> handler { (req: Request) =>
        parseBody[ProposeCommitmentRequest](req).flatMap {
          case None => ZIO.succeed(Response.json("""{"error":"invalid body"}""").status(Status.BadRequest))
          case Some(body) =>
            service.proposeCommitment(body)
              .map(c => Response.json(c.toJson).status(Status.Created))
              .catchAll(e => ZIO.succeed(errorResponse(e.getMessage)))
        }
      },

      Method.GET / "commitments" -> handler { (req: Request) =>
        val owner  = queryParam(req, "owner")
        val scope  = queryParam(req, "scope")
        val status = queryParam(req, "status")
        service.listCommitments(owner, scope, status)
          .map(cs => Response.json(cs.toJson))
          .orElse(ZIO.succeed(Response.status(Status.InternalServerError)))
      },

      Method.POST / "memory" / "propose" -> handler { (req: Request) =>
        parseBody[ProposeMemoryRequest](req).flatMap {
          case None => ZIO.succeed(Response.json("""{"error":"invalid body"}""").status(Status.BadRequest))
          case Some(body) =>
            service.proposeMemory(body)
              .map(m => Response.json(m.toJson).status(Status.Created))
              .catchAll(e => ZIO.succeed(errorResponse(e.getMessage)))
        }
      },

      Method.GET / "memory" -> handler { (req: Request) =>
        val personId = queryParam(req, "person")
        val scopeId  = queryParam(req, "scope")
        service.listMemory(personId, scopeId)
          .map(ms => Response.json(ms.toJson))
          .orElse(ZIO.succeed(Response.status(Status.InternalServerError)))
      },

      Method.POST / "approvals" / "request" -> handler { (req: Request) =>
        parseBody[RequestApprovalRequest](req).flatMap {
          case None => ZIO.succeed(Response.json("""{"error":"invalid body"}""").status(Status.BadRequest))
          case Some(body) =>
            service.requestApproval(body)
              .map(a => Response.json(a.toJson).status(Status.Created))
              .catchAll(e => ZIO.succeed(errorResponse(e.getMessage)))
        }
      },

      Method.GET / "approvals" -> handler { (req: Request) =>
        val scopeId = queryParam(req, "scope")
        val status  = queryParam(req, "status")
        service.listApprovals(scopeId, status)
          .map(as => Response.json(as.toJson))
          .orElse(ZIO.succeed(Response.status(Status.InternalServerError)))
      }
    )

  private def queryParam(req: Request, name: String): Option[String] =
    req.url.queryParams.getAll(name).headOption

  private def parseBody[A: JsonDecoder](req: Request): ZIO[Any, Nothing, Option[A]] =
    req.body.asString.orDie.flatMap { body =>
      ZIO.fromEither(body.fromJson[A]).mapBoth(_ => (), Some(_))
    }.orElseSucceed(None)

  private def errorResponse(message: String): Response =
    Response.json(ErrorResponse(message, None).toJson).status(Status.BadRequest)
}
