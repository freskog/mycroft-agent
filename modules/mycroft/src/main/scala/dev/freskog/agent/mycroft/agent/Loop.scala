package dev.freskog.agent.mycroft.agent

import dev.freskog.agent.common._
import dev.freskog.agent.mycroft.domain.{AgentEvent, MycroftConfig}
import dev.freskog.agent.mycroft.llm._
import dev.freskog.agent.mycroft.tools.{PersonClient, SkillProvider, ToolRegistry, ToolOutcome}

import zio._
import zio.json._
import zio.json.ast.Json

import java.time.{Instant, ZoneId}

private[agent] final case class CallB(id: String, name: String, args: String)

/** Per-LLM-call accounting, folded while streaming one completion. `startNanos`
 *  is when we began pulling the stream; `firstTokenNanos` the first streamed
 *  token (reasoning or content); `lastTokenNanos` the most recent one. */
private[agent] final case class Accum(
  content: String,
  calls: Map[Int, CallB],
  finish: Option[String],
  startNanos: Long = 0L,
  firstTokenNanos: Option[Long] = None,
  lastTokenNanos: Option[Long] = None,
  usage: Option[ChatUsage] = None,
  timings: Option[ChatTimings] = None
)

/** Throughput/latency for one model call, derived from an `Accum`. TTFT and
 *  generation duration are measured client-side; token counts come from the
 *  server `usage` chunk; tok/s prefer the server `timings` block when present. */
private[agent] final case class CallMetrics(
  promptTokens: Option[Int],
  completionTokens: Option[Int],
  ttftMs: Option[Long],
  genTps: Option[Double],
  ppTps: Option[Double]
)

/** A tool call the loop observed (any depth). For skill executions these are
 *  rolled up into the result contract's `actions` + `artifacts`. */
private[agent] final case class ObservedAction(tool: String, ok: Boolean, summary: String, artifact: Option[String])

/** The outcome of one (possibly nested) loop run: the model's final text, the
 *  tool calls observed at this level, and whether the shared budget ran out. */
private[agent] final case class LoopResult(content: String, actions: List[ObservedAction], hitCap: Boolean, metrics: Option[CallMetrics] = None)

/** The turn lifecycle: resolve scopes, build the prompt, stream the LLM, run
 *  native tool calls and skill compositions, and publish typed events to the
 *  outbound hub. The same `runLoop` engine drives both the top-level turn and
 *  every nested skill execution; only the system prompt, seed messages, and
 *  recursion depth differ. Designed to never fail its fiber — terminal problems
 *  surface as an `error` event. */
final class Loop(
  config: MycroftConfig,
  person: PersonClient,
  llm: LmStudioClient,
  tools: ToolRegistry,
  memory: MemoryProvider,
  skills: SkillProvider,
  hub: Hub[AgentEvent]
) {

  def run(channel: String, from: String, content: String, externalId: Option[String], turnId: String): UIO[Unit] = {
    val senderId = PersonId(from)

    val turn = for {
      t0      <- Clock.nanoTime
      stored   = storedContent(content)
      _       <- person.appendMessage(ChannelId(channel), MessageRole.User, Some(senderId), stored, None, externalId)
      bundle  <- memory.recall(5, 3)
      profile <- memory.profile(50)
      graph   <- memory.household
      model   <- resolveModel(channel)
      // Router (no dedicated LLM call): surface candidate skills into the main
      // loop's context; the model decides to run_skill or answer directly.
      cands   <- skills.search(content, 5).catchAll(_ => ZIO.succeed(Nil))
      now     <- Clock.instant
      sys      = Prompt.system(senderId, bundle, profile, graph, now, zoneId, cands)
      history <- historyWindow(channel, Prompt.estimateTokens(sys))
      msgs     = ChatMessage.system(sys) :: (if (stored != content) history.dropRight(1) :+ ChatMessage.user(content) else history)
      _       <- hub.publish(AgentEvent.Started(channel, turnId, externalId, model, Instant.now()))
      iters   <- Ref.make(config.maxToolIterations)
      result  <- runLoop(channel, turnId, senderId, model, msgs, config.maxSkillDepth, iters, Set.empty)
      _       <- ZIO.when(result.hitCap)(
                   hub.publish(AgentEvent.Error(channel, turnId, "max_tool_iterations", s"stopped after ${config.maxToolIterations} tool iterations"))
                 )
      _       <- finalize(channel, turnId, result.content, t0, result.metrics)
    } yield ()

    turn
      .timeoutFail(AgentError.Bug(s"turn exceeded ${config.maxTurnSeconds}s"))(config.maxTurnSeconds.seconds)
      .catchAll(e => hub.publish(AgentEvent.Error(channel, turnId, kindOf(e), e.message)) *> persistFailure(channel, e).ignore)
      .unit
  }

  /** Trigger a named skill as the whole turn (channel adapters like the REPL use
   *  this for `/triage 5` => inbox-triage(limit=5)). The channel only names the
   *  skill + task; orchestration lives in the skill playbook. The reply the user
   *  sees is the skill's final summary. */
  def runSkill(channel: String, from: String, skillName: String, task: String, params: Option[String], turnId: String): UIO[Unit] = {
    val senderId = PersonId(from)

    val turn = for {
      t0      <- Clock.nanoTime
      stored   = s"/skill $skillName: $task"
      _       <- person.appendMessage(ChannelId(channel), MessageRole.User, Some(senderId), stored, None, None)
      model   <- resolveModel(channel)
      _       <- hub.publish(AgentEvent.Started(channel, turnId, None, model, Instant.now()))
      iters   <- Ref.make(config.maxToolIterations)
      result  <- skills.find(skillName).flatMap {
                   case None =>
                     val msg = s"No skill named '$skillName'."
                     hub.publish(AgentEvent.ToolResult(channel, turnId, s"skill:$skillName", ok = false, msg))
                       .as(LoopResult(msg, Nil, hitCap = false))
                   case Some(skill) =>
                     hub.publish(AgentEvent.ToolCall(channel, turnId, s"skill:$skillName", task)) *> {
                       for {
                         ctxBlock <- skillContext(senderId, task)
                         now      <- Clock.instant
                         sys       = skillSystemPrompt(skill.name, skill.body, task, params, senderId, ctxBlock, now)
                         seed      = List(ChatMessage.system(sys), ChatMessage.user(skillSeed(task, params)))
                         r        <- runLoop(channel, turnId, senderId, model, seed, config.maxSkillDepth - 1, iters, Set.empty)
                         _        <- hub.publish(AgentEvent.ToolResult(channel, turnId, s"skill:$skillName", ok = !r.hitCap, contractLine(r)))
                         _        <- persistSkillResult(channel, skillName, r.content).ignore
                       } yield r
                     }
                 }
      _       <- ZIO.when(result.hitCap)(
                   hub.publish(AgentEvent.Error(channel, turnId, "max_tool_iterations", s"stopped after ${config.maxToolIterations} tool iterations"))
                 )
      _       <- finalize(channel, turnId, result.content, t0, result.metrics)
    } yield ()

    turn
      .timeoutFail(AgentError.Bug(s"turn exceeded ${config.maxTurnSeconds}s"))(config.maxTurnSeconds.seconds)
      .catchAll(e => hub.publish(AgentEvent.Error(channel, turnId, kindOf(e), e.message)) *> persistFailure(channel, e).ignore)
      .unit
  }

  // --- the shared loop engine (top-level turn AND skill executions) ---

  /** Stream one completion, run any tool calls, recurse. Iterations are drawn
   *  from a single shared `Ref` so nested skill executions inherit the parent's
   *  remaining budget and can never extend it. Returns the final content plus
   *  the tool calls observed at THIS level (nested calls stay in the child's
   *  contract). */
  private def runLoop(
    channel: String, turnId: String, sender: PersonId, model: String,
    msgs: List[ChatMessage], depthLeft: Int, iters: Ref[Int], seen: Set[String]
  ): IO[AgentError, LoopResult] =
    iters.get.flatMap { left =>
      if (left <= 0) ZIO.succeed(LoopResult(lastContent(msgs), Nil, hitCap = true))
      else
        iters.update(_ - 1) *>
          collect(channel, turnId, model, msgs).flatMap { acc =>
            val ordered = acc.calls.toList.sortBy(_._1).map(_._2).filter(_.name.nonEmpty)
            if (ordered.isEmpty) ZIO.succeed(LoopResult(finalReply(acc), Nil, hitCap = false, metrics = Some(metricsOf(acc))))
            else {
              val assistant = ChatMessage("assistant", Some(acc.content), toolCalls = ordered.map(c => ToolCallSpec(c.id, c.name, c.args)))
              runCalls(channel, turnId, sender, model, ordered, depthLeft, iters, seen).flatMap {
                case (results, seen2, acts) =>
                  runLoop(channel, turnId, sender, model, msgs ++ (assistant :: results), depthLeft, iters, seen2)
                    .map(rest => rest.copy(actions = acts ++ rest.actions))
              }
            }
          }
    }

  /** Execute the ordered tool calls, threading the call-signature set so an
   *  identical (name+args) call — including a repeated `run_skill(name, task)` —
   *  is short-circuited instead of re-run. */
  private def runCalls(
    channel: String, turnId: String, sender: PersonId, model: String,
    calls: List[CallB], depthLeft: Int, iters: Ref[Int], seen: Set[String]
  ): IO[AgentError, (List[ChatMessage], Set[String], List[ObservedAction])] =
    calls match {
      case Nil => ZIO.succeed((Nil, seen, Nil))
      case c :: rest =>
        val sig = s"${c.name}|${c.args}"
        val step: IO[AgentError, (ChatMessage, Option[ObservedAction])] =
          if (seen.contains(sig)) repeatedCall(channel, turnId, c).map((_, None))
          else dispatchCall(channel, turnId, sender, model, c, depthLeft, iters)
        for {
          res                     <- step
          (msg, act)               = res
          more                    <- runCalls(channel, turnId, sender, model, rest, depthLeft, iters, seen + sig)
          (restMsgs, s2, restActs) = more
        } yield (msg :: restMsgs, s2, act.toList ++ restActs)
    }

  private def dispatchCall(
    channel: String, turnId: String, sender: PersonId, model: String,
    c: CallB, depthLeft: Int, iters: Ref[Int]
  ): IO[AgentError, (ChatMessage, Option[ObservedAction])] =
    if (c.name == SkillTools.ToolName) executeSkill(channel, turnId, sender, model, c, depthLeft, iters)
    else runCall(channel, turnId, c)

  private def repeatedCall(channel: String, turnId: String, c: CallB): IO[AgentError, ChatMessage] = {
    val note = "You already ran this exact command in this turn and it did not help. Do NOT repeat it. " +
      "If you were trying to read output that was truncated, use the runlog tool on the run_id from that earlier preview " +
      "(e.g. runlog(\"show <run_id> --stream stdout --tail 200\")) instead of re-running with different pipes. " +
      "Otherwise try a genuinely different command or answer with what you already know."
    hub.publish(AgentEvent.ToolCall(channel, turnId, c.name, c.args)) *>
      hub.publish(AgentEvent.ToolResult(channel, turnId, c.name, ok = false, "repeated call skipped")).as(
        ChatMessage("tool", Some(s"Error: $note"), toolCallId = Some(c.id))
      )
  }

  /** Stream one completion, publishing reasoning/content live and folding the
   *  tool-call argument fragments by index. The advertised tool set is OS tools
   *  (safe_run + runlog) plus the run_skill control-plane tool. */
  private val sampling: SamplingParams =
    SamplingParams(config.temperature, config.topP, config.topK, config.minP, config.presencePenalty)

  /** Configured timezone for the prompt clock; fall back to UTC if the configured
   *  id is unparseable rather than failing the turn. */
  private val zoneId: ZoneId =
    scala.util.Try(ZoneId.of(config.timezone)).getOrElse(ZoneId.of("UTC"))

  private def collect(channel: String, turnId: String, model: String, msgs: List[ChatMessage]): IO[AgentError, Accum] =
    Clock.nanoTime.flatMap { start =>
      llm.chat(model, msgs, config.maxOutputTokens, Some(SkillTools.allToolsJson), sampling)
        .runFoldZIO(Accum("", Map.empty, None, startNanos = start)) { (acc, chunk) =>
          val isToken = chunk.reasoningDelta.isDefined || chunk.contentDelta.isDefined
          for {
            now <- Clock.nanoTime
            _   <- chunk.reasoningDelta.fold(ZIO.unit: IO[AgentError, Unit])(d => hub.publish(AgentEvent.Reasoning(channel, turnId, d)).unit)
            _   <- chunk.contentDelta.fold(ZIO.unit: IO[AgentError, Unit])(d => hub.publish(AgentEvent.Content(channel, turnId, d)).unit)
          } yield acc.copy(
            content         = acc.content + chunk.contentDelta.getOrElse(""),
            calls           = chunk.toolCallDeltas.foldLeft(acc.calls)(mergeDelta),
            finish          = chunk.finishReason.orElse(acc.finish),
            firstTokenNanos = acc.firstTokenNanos.orElse(if (isToken) Some(now) else None),
            lastTokenNanos  = if (isToken) Some(now) else acc.lastTokenNanos,
            usage           = chunk.usage.orElse(acc.usage),
            timings         = chunk.timings.orElse(acc.timings)
          )
        }
    }

  /** Derive throughput/latency from a completed call's accumulator. Prefers
   *  server-reported tok/s; otherwise computes from client-measured durations
   *  and the usage token counts. */
  private def metricsOf(acc: Accum): CallMetrics = {
    val ttftMs = acc.firstTokenNanos.map(f => (f - acc.startNanos) / 1_000_000)
    val genMs  = for { f <- acc.firstTokenNanos; l <- acc.lastTokenNanos } yield (l - f) / 1_000_000
    val prompt = acc.usage.flatMap(_.promptTokens)
    val comp   = acc.usage.flatMap(_.completionTokens)
    val genTps = acc.timings.flatMap(_.predictedPerSecond).orElse(
      for { c <- comp; g <- genMs if g > 0 } yield c * 1000.0 / g
    )
    val ppTps = acc.timings.flatMap(_.promptPerSecond).orElse(
      for { p <- prompt; t <- ttftMs if t > 0 } yield p * 1000.0 / t
    )
    CallMetrics(prompt, comp, ttftMs, genTps, ppTps)
  }

  /** Never finalize a blank turn. If the model produced no tool call and no
   *  text, it almost always spun in a reasoning loop until it hit the token cap
   *  (`finish_reason: length`); surface a clear message instead of an empty
   *  reply so the user isn't left staring at a bare timing line. */
  private def finalReply(acc: Accum): String =
    if (acc.content.trim.nonEmpty) acc.content
    else if (acc.finish.contains("length"))
      "I ran out of room mid-thought before I could answer — I may have over-thought it. Try asking again, or narrow the request."
    else
      "I wasn't able to produce a response to that. Could you rephrase or give me a bit more to go on?"

  private def mergeDelta(calls: Map[Int, CallB], d: ToolCallDelta): Map[Int, CallB] = {
    val cur = calls.getOrElse(d.index, CallB("", "", ""))
    calls.updated(d.index, CallB(
      id   = d.id.getOrElse(cur.id),
      name = d.name.getOrElse(cur.name),
      args = cur.args + d.argsFragment.getOrElse("")
    ))
  }

  /** One OS tool call (safe_run / runlog). */
  private def runCall(channel: String, turnId: String, c: CallB): IO[AgentError, (ChatMessage, Option[ObservedAction])] =
    for {
      _       <- hub.publish(AgentEvent.ToolCall(channel, turnId, c.name, c.args))
      outcome <- tools.dispatch(c.name, c.args).catchAll(e => ZIO.succeed(ToolOutcome(ok = false, e.message, s"Error: ${e.message}")))
      _       <- hub.publish(AgentEvent.ToolResult(channel, turnId, c.name, outcome.ok, outcome.summary))
      _       <- logDecision(c, outcome).ignore
      action   = ObservedAction(c.name, outcome.ok, outcome.summary, extractRunlogRef(outcome.content))
    } yield (ChatMessage("tool", Some(outcome.content), toolCallId = Some(c.id)), Some(action))

  // --- skill composition (run_skill) ---

  /** Run a skill as an isolated sub-task: fresh context (skill body + task +
   *  entry points + injected memory), the parent's remaining budget, depth-1,
   *  and a structured result contract back to the parent. Inner reasoning/tool
   *  events still stream to the user under the same turnId, bracketed by a
   *  synthetic skill tool_call/tool_result, but only the contract re-enters the
   *  parent's message list. */
  private def executeSkill(
    channel: String, turnId: String, sender: PersonId, model: String,
    c: CallB, depthLeft: Int, iters: Ref[Int]
  ): IO[AgentError, (ChatMessage, Option[ObservedAction])] = {
    val parsed = c.args.fromJson[Json].toOption
    val name   = parsed.flatMap(strField("name")).map(_.trim).filter(_.nonEmpty)
    val task   = parsed.flatMap(strField("task")).getOrElse("")
    val params = parsed.flatMap(strField("params")).filter(_.trim.nonEmpty)

    name match {
      case None =>
        toolError(c, "run_skill requires a non-empty 'name'. Use `skill list` to see available skills.")
      case Some(_) if depthLeft <= 0 =>
        toolError(c, "skill recursion depth cap reached — cannot nest another skill. Do the work directly with safe_run, or summarise.")
      case Some(skillName) =>
        hub.publish(AgentEvent.ToolCall(channel, turnId, s"skill:$skillName", task)) *>
          skills.find(skillName).flatMap {
            case None =>
              val msg = s"no skill named '$skillName'. Use `skill list` to see available skills."
              hub.publish(AgentEvent.ToolResult(channel, turnId, s"skill:$skillName", ok = false, msg)) *>
                toolError(c, msg)
            case Some(skill) =>
              for {
                ctxBlock <- skillContext(sender, task)
                now      <- Clock.instant
                sys       = skillSystemPrompt(skill.name, skill.body, task, params, sender, ctxBlock, now)
                seed      = List(ChatMessage.system(sys), ChatMessage.user(skillSeed(task, params)))
                result   <- runLoop(channel, turnId, sender, model, seed, depthLeft - 1, iters, Set.empty)
                contract  = buildContract(result)
                line      = contractLine(result)
                _        <- hub.publish(AgentEvent.ToolResult(channel, turnId, s"skill:$skillName", ok = !result.hitCap, line))
                _        <- persistSkillResult(channel, skillName, result.content).ignore
                action    = ObservedAction(s"skill:$skillName", ok = !result.hitCap, line, None)
              } yield (ChatMessage("tool", Some(contract), toolCallId = Some(c.id)), Some(action))
          }
    }
  }

  private def toolError(c: CallB, message: String): IO[AgentError, (ChatMessage, Option[ObservedAction])] =
    ZIO.succeed((ChatMessage("tool", Some(s"Error: $message"), toolCallId = Some(c.id)), Some(ObservedAction(c.name, ok = false, message, None))))

  /** Memory injected into a skill execution: the pinned owner/household profile
   *  and graph, the generic decaying recall bundle, PLUS a task-relevant search,
   *  merged and de-duplicated by text. */
  private def skillContext(sender: PersonId, task: String): IO[AgentError, String] =
    (for {
      profile <- memory.profile(50)
      graph   <- memory.household
      bundle  <- memory.recall(5, 3)
      hits    <- memory.search(task, Some(sender), 5)
    } yield {
      val profileFacts = profile.map(m => s"  - ${m.text}")
      val entityFacts  = graph.entities.map(e => s"  - ${e.name} (${EntityKind.asString(e.kind)})")
      val relFacts     = graph.relationships.map(r => s"  - ${r.fromId} —${r.relType}→ ${r.toId}")
      val bundleFacts  = bundle.facts.map(h => s"  - ${h.item.text}")
      val searchFacts  = hits.map(h => s"  - ${h.item.text}")
      val merged       = (profileFacts ++ entityFacts ++ relFacts ++ bundleFacts ++ searchFacts).distinct
      if (merged.isEmpty) "  (no relevant memory)" else merged.mkString("\n")
    }).catchAll(_ => ZIO.succeed("  (memory unavailable)"))

  private def skillSystemPrompt(name: String, body: String, task: String, params: Option[String], sender: PersonId, ctxBlock: String, now: Instant): String = {
    val paramLine = params.map(p => s"Parameters: $p\n").getOrElse("")
    s"""You are running the "$name" skill as an isolated sub-task on behalf of ${sender.value}.
       |Write access is propose-only — a human accepts proposals before they become durable.
       |
       |${Prompt.clockLine(now, zoneId)}
       |
       |Your task: $task
       |$paramLine
       |Follow the playbook below. Use `safe_run` for commands and `runlog` to inspect
       |truncated output; you may call `run_skill` to compose another skill. Do only
       |what the task needs. When finished, your final message must be a concise summary
       |of what you did and the outcome — that summary is the ONLY thing returned to the
       |caller, so make it self-contained.
       |
       |--- SKILL: $name ---
       |$body
       |--- END SKILL ---
       |
       |Relevant memory:
       |$ctxBlock""".stripMargin
  }

  private def skillSeed(task: String, params: Option[String]): String =
    params.map(p => s"$task\n\nParameters: $p").getOrElse(task)

  /** `{ status, summary, actions: [...], artifacts: [runlog ids] }`. The harness
   *  synthesizes actions + artifacts from the execution; the model supplies the
   *  summary (its final message) and the status follows from whether the budget
   *  held. */
  private def buildContract(r: LoopResult): String = {
    val actions = Json.Arr(Chunk.fromIterable(r.actions.map(a =>
      Json.Obj("tool" -> Json.Str(a.tool), "ok" -> Json.Bool(a.ok), "summary" -> Json.Str(a.summary))
    )))
    val artifacts = Json.Arr(Chunk.fromIterable(r.actions.flatMap(_.artifact).distinct.map(Json.Str(_))))
    Json.Obj(
      "status"    -> Json.Str(if (r.hitCap) "incomplete" else "ok"),
      "summary"   -> Json.Str(r.content),
      "actions"   -> actions,
      "artifacts" -> artifacts
    ).toJson
  }

  private def contractLine(r: LoopResult): String = {
    val status = if (r.hitCap) "incomplete" else "ok"
    val head   = r.content.linesIterator.find(_.trim.nonEmpty).getOrElse("").trim.take(160)
    s"$status — ${r.actions.size} action(s)" + (if (head.nonEmpty) s": $head" else "")
  }

  /** Persist the skill result summary as a durable session_note so it survives
   *  the turn and re-enters future context bundles; raw detail stays in runlog. */
  private def persistSkillResult(channel: String, skillName: String, summary: String): IO[AgentError, Unit] =
    if (summary.trim.isEmpty) ZIO.unit
    else person.logEvent("mycroft", s"skill.$skillName", EventCategory.SessionNote, Some(summary), None)

  private def finalize(channel: String, turnId: String, reply: String, t0: Long, metrics: Option[CallMetrics]): IO[AgentError, Unit] =
    for {
      t1 <- Clock.nanoTime
      _  <- person.appendMessage(ChannelId(channel), MessageRole.Assistant, None, reply, None, Some(turnId))
      tokensIn  = metrics.flatMap(_.promptTokens).getOrElse(0)
      tokensOut = metrics.flatMap(_.completionTokens).getOrElse(Prompt.estimateTokens(reply))
      _  <- hub.publish(AgentEvent.Done(
              channel, turnId, "stop", tokensIn, tokensOut, (t1 - t0) / 1_000_000,
              ttftMs = metrics.flatMap(_.ttftMs),
              genTps = metrics.flatMap(_.genTps),
              ppTps  = metrics.flatMap(_.ppTps)
            ))
    } yield ()

  // --- helpers ---

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
    person.logEvent("mycroft", s"tool.${c.name}", EventCategory.Decision, Some(outcome.summary), Some(payload))
  }

  private def persistFailure(channel: String, e: AgentError): IO[AgentError, Message] =
    person.appendMessage(ChannelId(channel), MessageRole.System, None, s"turn failed: ${e.message}", None, None)

  private def lastContent(msgs: List[ChatMessage]): String =
    msgs.reverse.collectFirst { case ChatMessage("assistant", Some(c), _, _) if c.nonEmpty => c }.getOrElse("")

  private val RunlogRef = """\(full output: runlog (\S+)""".r

  private def extractRunlogRef(content: String): Option[String] =
    RunlogRef.findFirstMatchIn(content).map(_.group(1))

  private def strField(name: String)(json: Json): Option[String] = json match {
    case Json.Obj(fields) => fields.collectFirst { case (k, Json.Str(s)) if k == name => s }
    case _                => None
  }

  /** Keep triage payloads out of channel history — the full prompt is still sent to the LLM. */
  private def storedContent(content: String): String =
    if (content.startsWith("TRIAGE MODE")) {
      val n = content.split("\"inboxId\"").length - 1
      if (n > 0) s"/triage batch ($n messages)" else "/triage batch"
    } else content

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
