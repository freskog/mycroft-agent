package dev.freskog.agent.mycroft.api

import dev.freskog.agent.common._
import dev.freskog.agent.common.JsonCodecs._
import dev.freskog.agent.mycroft.agent.Loop
import dev.freskog.agent.mycroft.domain.AgentEvent
import dev.freskog.agent.mycroft.llm.LmStudioClient
import dev.freskog.agent.mycroft.tools.PersonClient

import zio._
import zio.http._
import zio.stream._
import zio.json._

import java.nio.charset.StandardCharsets

object Routes {

  final case class InboundRequest(
    channel: String,
    from: String,
    content: String,
    @jsonField("external_id") externalId: Option[String],
    skill: Option[String] = None,
    params: Option[String] = None
  )
  object InboundRequest {
    implicit val codec: JsonCodec[InboundRequest] = DeriveJsonCodec.gen[InboundRequest]
  }

  final case class ChannelCreate(
    id: String,
    defaultModel: Option[String],
    members: Option[List[String]]
  )
  object ChannelCreate {
    implicit val codec: JsonCodec[ChannelCreate] = DeriveJsonCodec.gen[ChannelCreate]
  }

  /** A running turn we can interrupt on request: its channel (for the terminal
   *  event) and the daemon fiber executing it. */
  private final case class RunningTurn(channel: String, fiber: Fiber.Runtime[Nothing, Unit])

  def make(loop: Loop, person: PersonClient, llm: LmStudioClient, hub: Hub[AgentEvent]): zio.http.Routes[Any, Response] = {
    // turnId -> running turn, so a client (e.g. the REPL pressing ESC) can cancel
    // a turn instead of waiting for it to finish or time out.
    val running = new java.util.concurrent.ConcurrentHashMap[String, RunningTurn]()

    zio.http.Routes(

      Method.GET / "health" -> handler { (_: Request) =>
        Response.json("""{"status":"ok"}""")
      },

      Method.POST / "inbound" -> handler { (req: Request) =>
        parseBody[InboundRequest](req).flatMap {
          case Left(err) => ZIO.succeed(errorToResponse(err))
          case Right(in) =>
            val turnId = java.util.UUID.randomUUID().toString
            val start  = in.skill.map(_.trim).filter(_.nonEmpty) match {
              case Some(skill) => loop.runSkill(in.channel, in.from, skill, in.content, in.params, turnId)
              case None        => loop.run(in.channel, in.from, in.content, in.externalId, turnId)
            }
            start
              .ensuring(ZIO.succeed(running.remove(turnId)))
              .forkDaemon
              .flatMap(fib => ZIO.succeed(running.put(turnId, RunningTurn(in.channel, fib))))
              .as(Response.json(s"""{"message_id":"$turnId","channel":"${in.channel}"}""").status(Status.Accepted))
        }
      },

      // Cancel an in-flight turn: interrupt the fiber and publish a terminal
      // `error` event so any connected stream closes the turn cleanly.
      Method.POST / "turns" / string("id") / "cancel" -> handler { (id: String, _: Request) =>
        Option(running.remove(id)) match {
          case None      => ZIO.succeed(Response.json(s"""{"cancelled":null}""").status(Status.NotFound))
          case Some(rt)  =>
            rt.fiber.interrupt *>
              hub.publish(AgentEvent.Error(rt.channel, id, "cancelled", "turn cancelled by user"))
                .as(Response.json(s"""{"cancelled":"$id"}"""))
        }
      },

      Method.GET / "outbound" / "stream" -> handler { (_: Request) =>
        val greeting = ZStream.succeed(": connected\n\n")
        val events   = ZStream.fromHub(hub).map(Sse.frame)
        val bytes    = (greeting ++ events).mapConcatChunk(s => Chunk.fromArray(s.getBytes(StandardCharsets.UTF_8)))
        ZIO.succeed(
          Response(body = Body.fromStreamChunked(bytes))
            .addHeader("Content-Type", "text/event-stream")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Connection", "keep-alive")
        )
      },

      Method.GET / "models" -> handler { (_: Request) =>
        llm.listModels.fold(errorToResponse, ms => Response.json(ms.toJson))
      },

      Method.POST / "channels" -> handler { (req: Request) =>
        parseBody[ChannelCreate](req).flatMap {
          case Left(err) => ZIO.succeed(errorToResponse(err))
          case Right(c)  =>
            person.createChannel(ChannelId(c.id), c.defaultModel, c.members.getOrElse(Nil).map(PersonId))
              .fold(errorToResponse, cw => Response.json(cw.toJson).status(Status.Created))
        }
      },

      Method.GET / "channels" -> handler { (_: Request) =>
        person.listChannels.fold(errorToResponse, cs => Response.json(cs.toJson))
      },

      Method.GET / "channels" / string("id") -> handler { (id: String, _: Request) =>
        person.getChannel(ChannelId(id)).fold(
          errorToResponse,
          {
            case Some(cw) => Response.json(cw.toJson)
            case None     => errorToResponse(AgentError.NotFound("channel", id))
          }
        )
      },

      Method.GET / "messages" -> handler { (req: Request) =>
        req.url.queryParams.getAll("channel").headOption match {
          case None => ZIO.succeed(errorToResponse(AgentError.BadRequest("channel is required")))
          case Some(ch) =>
            val limit = req.url.queryParams.getAll("limit").headOption.flatMap(_.toIntOption).getOrElse(50)
            person.listMessages(ChannelId(ch), None, limit).fold(errorToResponse, ms => Response.json(ms.toJson))
        }
      }
    )
  }

  private def parseBody[A: JsonDecoder](req: Request): UIO[Either[AgentError, A]] =
    req.body.asString
      .mapError(t => AgentError.BadRequest(s"reading body: ${Option(t.getMessage).getOrElse("io error")}"))
      .flatMap(raw => ZIO.fromEither(raw.fromJson[A]).mapError(msg => AgentError.BadRequest(s"invalid JSON body: $msg")))
      .either

  private def errorToResponse(e: AgentError): Response = {
    val status = e match {
      case _: AgentError.NotFound      => Status.NotFound
      case _: AgentError.BadRequest    => Status.BadRequest
      case _: AgentError.Validation    => Status.BadRequest
      case _: AgentError.DecodeFailed  => Status.BadGateway
      case _: AgentError.Persistence   => Status.InternalServerError
      case _: AgentError.HttpFailed    => Status.BadGateway
      case _: AgentError.HttpBadStatus => Status.BadGateway
      case _: AgentError.Bug           => Status.InternalServerError
    }
    Response.json(e.toJson).status(status)
  }
}
