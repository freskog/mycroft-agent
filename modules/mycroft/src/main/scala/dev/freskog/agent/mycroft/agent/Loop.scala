package dev.freskog.agent.mycroft.agent

import dev.freskog.agent.common._
import dev.freskog.agent.mycroft.domain.{AgentEvent, MycroftConfig}
import dev.freskog.agent.mycroft.llm._
import dev.freskog.agent.mycroft.tools.{PersonClient, ToolRegistry, ToolOutcome}

import zio._
import zio.json.ast.Json

import java.time.Instant

private[agent] final case class CallB(id: String, name: String, args: String)
private[agent] final case class Accum(content: String, calls: Map[Int, CallB], finish: Option[String])

/** The turn lifecycle: resolve scopes, build the prompt, stream the LLM, run
 *  native tool calls, and publish typed events to the outbound hub. Designed to
 *  never fail its fiber — terminal problems surface as an `error` event. */
final class Loop(
  config: MycroftConfig,
  person: PersonClient,
  llm: LmStudioClient,
  tools: ToolRegistry,
  hub: Hub[AgentEvent]
) {

  def run(channel: String, from: String, content: String, externalId: Option[String], turnId: String): UIO[Unit] = {
    val senderId = PersonId(from)

    val turn = for {
      roles   <- person.scopeRoles(senderId)
      _       <- person.appendMessage(ChannelId(channel), MessageRole.User, Some(senderId), content, None, externalId)
      bundles <- gatherContext(roles.map(_.scopeId))
      model   <- resolveModel(channel)
      sys      = Prompt.system(senderId, roles, bundles)
      history <- historyWindow(channel, Prompt.estimateTokens(sys))
      msgs     = ChatMessage.system(sys) :: history
      _       <- hub.publish(AgentEvent.Started(channel, turnId, externalId, model, Instant.now()))
      _       <- iterate(channel, turnId, senderId, model, msgs, 1, Set.empty)
    } yield ()

    turn
      .timeoutFail(AgentError.Bug(s"turn exceeded ${config.maxTurnSeconds}s"))(config.maxTurnSeconds.seconds)
      .catchAll(e => hub.publish(AgentEvent.Error(channel, turnId, kindOf(e), e.message)) *> persistFailure(channel, e).ignore)
      .unit
  }

  // --- iteration ---

  private def iterate(channel: String, turnId: String, sender: PersonId, model: String, msgs: List[ChatMessage], iter: Int, seen: Set[String]): IO[AgentError, Unit] =
    if (iter > config.maxToolIterations)
      hub.publish(AgentEvent.Error(channel, turnId, "max_tool_iterations", s"stopped after ${config.maxToolIterations} tool iterations")) *>
        finalize(channel, turnId, lastContent(msgs))
    else
      collect(channel, turnId, model, msgs).flatMap { acc =>
        val ordered = acc.calls.toList.sortBy(_._1).map(_._2).filter(_.name.nonEmpty)
        if (ordered.isEmpty) finalize(channel, turnId, acc.content)
        else
          for {
            assistant       <- ZIO.succeed(ChatMessage("assistant", Some(acc.content), toolCalls = ordered.map(c => ToolCallSpec(c.id, c.name, c.args))))
            resultsAndSeen  <- runCalls(channel, turnId, sender, ordered, seen)
            (results, seen2) = resultsAndSeen
            _               <- iterate(channel, turnId, sender, model, msgs ++ (assistant :: results), iter + 1, seen2)
          } yield ()
      }

  /** Execute the ordered tool calls, threading a set of call signatures so an
   *  identical (name+args) call is short-circuited instead of re-run — this
   *  breaks the loop where a weak model fires the same failing command until it
   *  exhausts the iteration budget. */
  private def runCalls(channel: String, turnId: String, sender: PersonId, calls: List[CallB], seen: Set[String]): IO[AgentError, (List[ChatMessage], Set[String])] =
    calls match {
      case Nil => ZIO.succeed((Nil, seen))
      case c :: rest =>
        val sig = s"${c.name}|${c.args}"
        val step =
          if (seen.contains(sig)) repeatedCall(channel, turnId, c)
          else runCall(channel, turnId, sender, c)
        for {
          msg            <- step
          restAndSeen    <- runCalls(channel, turnId, sender, rest, seen + sig)
          (restMsgs, s2)  = restAndSeen
        } yield (msg :: restMsgs, s2)
    }

  private def repeatedCall(channel: String, turnId: String, c: CallB): IO[AgentError, ChatMessage] = {
    val note = "You already ran this exact command in this turn and it did not help. Do NOT repeat it — try a different command or answer the user with what you already know."
    hub.publish(AgentEvent.ToolCall(channel, turnId, c.name, c.args)) *>
      hub.publish(AgentEvent.ToolResult(channel, turnId, c.name, ok = false, "repeated call skipped")).as(
        ChatMessage("tool", Some(s"Error: $note"), toolCallId = Some(c.id))
      )
  }

  /** Stream one completion, publishing reasoning/content live and folding the
   *  tool-call argument fragments by index. */
  private def collect(channel: String, turnId: String, model: String, msgs: List[ChatMessage]): IO[AgentError, Accum] =
    llm.chat(model, msgs, config.maxOutputTokens, Some(ToolRegistry.toolsJson))
      .runFoldZIO(Accum("", Map.empty, None)) { (acc, chunk) =>
        for {
          _ <- chunk.reasoningDelta.fold(ZIO.unit: IO[AgentError, Unit])(d => hub.publish(AgentEvent.Reasoning(channel, turnId, d)).unit)
          _ <- chunk.contentDelta.fold(ZIO.unit: IO[AgentError, Unit])(d => hub.publish(AgentEvent.Content(channel, turnId, d)).unit)
        } yield Accum(
          content = acc.content + chunk.contentDelta.getOrElse(""),
          calls   = chunk.toolCallDeltas.foldLeft(acc.calls)(mergeDelta),
          finish  = chunk.finishReason.orElse(acc.finish)
        )
      }

  private def mergeDelta(calls: Map[Int, CallB], d: ToolCallDelta): Map[Int, CallB] = {
    val cur = calls.getOrElse(d.index, CallB("", "", ""))
    calls.updated(d.index, CallB(
      id   = d.id.getOrElse(cur.id),
      name = d.name.getOrElse(cur.name),
      args = cur.args + d.argsFragment.getOrElse("")
    ))
  }

  private def runCall(channel: String, turnId: String, sender: PersonId, c: CallB): IO[AgentError, ChatMessage] =
    for {
      _       <- hub.publish(AgentEvent.ToolCall(channel, turnId, c.name, c.args))
      outcome <- tools.dispatch(c.name, c.args).catchAll(e => ZIO.succeed(ToolOutcome(ok = false, e.message, s"Error: ${e.message}")))
      _       <- hub.publish(AgentEvent.ToolResult(channel, turnId, c.name, outcome.ok, outcome.summary))
      _       <- logDecision(c, outcome).ignore
    } yield ChatMessage("tool", Some(outcome.content), toolCallId = Some(c.id))

  private def finalize(channel: String, turnId: String, reply: String): IO[AgentError, Unit] =
    person.appendMessage(ChannelId(channel), MessageRole.Assistant, None, reply, None, Some(turnId)) *>
      hub.publish(AgentEvent.Done(channel, turnId, "stop", 0, Prompt.estimateTokens(reply))).unit

  // --- helpers ---

  private def gatherContext(scopes: List[ScopeId]): IO[AgentError, List[(ScopeId, ContextBundle)]] =
    ZIO.foreach(scopes.take(5)) { s =>
      person.contextBundle(s, 5, 3).map(b => s -> b).catchAll(_ => ZIO.succeed(s -> ContextBundle(Nil, Nil)))
    }

  private def historyWindow(channel: String, systemTokens: Int): IO[AgentError, List[ChatMessage]] = {
    val budget = math.max(512, config.contextTokenBudget - systemTokens)
    person.listMessages(ChannelId(channel), None, config.contextWindowMsgs * 2).map { msgs =>
      val convo = msgs.filter(m => m.role == MessageRole.User || m.role == MessageRole.Assistant)
      Compaction.window(convo, config.contextWindowMsgs, budget).map(toChat)
    }
  }

  private def toChat(m: Message): ChatMessage = m.role match {
    case MessageRole.Assistant => ChatMessage.assistant(m.content)
    case _                     => ChatMessage.user(m.content)
  }

  private def resolveModel(channel: String): IO[AgentError, String] =
    person.getChannel(ChannelId(channel)).map(_.flatMap(_.channel.defaultModel).getOrElse(config.defaultModel))
      .catchAll(_ => ZIO.succeed(config.defaultModel))

  private def logDecision(c: CallB, outcome: ToolOutcome): IO[AgentError, Unit] = {
    val payload = Json.Obj(
      "tool" -> Json.Str(c.name),
      "args" -> Json.Str(c.args),
      "ok"   -> Json.Bool(outcome.ok)
    ).toString
    person.logEvent("mycroft", s"tool.${c.name}", EventCategory.Decision, None, Some(outcome.summary), Some(payload))
  }

  private def persistFailure(channel: String, e: AgentError): IO[AgentError, Message] =
    person.appendMessage(ChannelId(channel), MessageRole.System, None, s"turn failed: ${e.message}", None, None)

  private def lastContent(msgs: List[ChatMessage]): String =
    msgs.reverse.collectFirst { case ChatMessage("assistant", Some(c), _, _) if c.nonEmpty => c }.getOrElse("")

  private def kindOf(e: AgentError): String = e match {
    case _: AgentError.HttpFailed    => "llm_unavailable"
    case _: AgentError.HttpBadStatus => "upstream_error"
    case _: AgentError.Validation    => "validation"
    case _: AgentError.NotFound      => "not_found"
    case _: AgentError.DecodeFailed  => "decode_failed"
    case _: AgentError.BadRequest    => "bad_request"
    case _: AgentError.Persistence   => "persistence"
    case _: AgentError.Bug           => "bug"
  }
}
