package dev.freskog.agent.mycroft

import dev.freskog.agent.mycroft.agent.{Loop, MemoryProvider}
import dev.freskog.agent.mycroft.api.Routes
import dev.freskog.agent.mycroft.domain.{AgentEvent, MycroftConfig}
import dev.freskog.agent.mycroft.llm.LmStudioClient
import dev.freskog.agent.mycroft.tools.{PersonClient, SkillProvider, ToolRegistry}
import dev.freskog.agent.runtime.SkillCatalog

import zio._
import zio.http._

object Main extends ZIOAppDefault {

  def run: ZIO[Any, Any, Any] = {
    val config = MycroftConfig.fromEnv

    ZIO.scoped {
      for {
        _      <- ZIO.logInfo(s"Starting mycroft on ${config.host}:${config.port} → person-service ${config.personServiceUrl}, LM Studio ${config.lmStudioUrl}")
        hub    <- Hub.sliding[AgentEvent](1024)
        person  = PersonClient.live(config.personServiceUrl)
        llm     = LmStudioClient.live(config.lmStudioUrl)
        tools   = ToolRegistry.live(ToolRegistry.defaultCwd, config.maxTurnSeconds)
        mem     = MemoryProvider.live(person)
        skills  = SkillProvider.live(SkillCatalog.resolveSkillsDir(None))
        loop    = new Loop(config, person, llm, tools, mem, skills, hub)
        routes  = Routes.make(loop, person, llm, hub)
        // Subscribe to person-service's approval stream: when a privileged action
        // executes, run its saga continuation (or a notification turn). Reconnects
        // if the stream drops. This is how an out-of-band approval (incl. a future
        // magic-link click) resumes the agent without person-service dialling out.
        _ <- person.subscribeApprovals(None)
               .filter(_.kind == "executed")
               .runForeach(ev => onExecuted(loop, ev))
               .tapError(e => ZIO.logWarning(s"approval stream error, reconnecting: ${e.message}"))
               .retry(Schedule.spaced(5.seconds))
               .forkScoped
        _ <- Server.serve(routes).provide(
          Server.defaultWith(_.binding(config.host, config.port).enableRequestStreaming)
        )
      } yield ()
    }
  }

  /** Resume after a privileged action executed: run the declared continuation
   *  skill, or — absent one — a notification turn so the agent acknowledges the
   *  user. Runs in the approval's originating channel. */
  private def onExecuted(loop: Loop, ev: dev.freskog.agent.common.ApprovalEvent): UIO[Unit] = {
    val a = ev.approval
    a.channel match {
      case None =>
        ZIO.logWarning(s"approval ${a.id.value} executed without a channel; cannot run continuation")
      case Some(channel) =>
        val from   = a.requiredPersonId.map(_.value).getOrElse(a.requestedBy)
        val turnId = java.util.UUID.randomUUID().toString
        a.continuationSkill match {
          case Some(skill) =>
            val task = s"Approval ${a.id.value} (${a.actionType}) was approved and executed; continue the workflow."
            loop.runSkill(channel, from, skill, task, a.continuationParams, turnId)
          case None =>
            val note = s"[approval ${a.id.value}] Your approval for ${a.actionType} was granted and the action completed. " +
              s"Result: ${a.resultJson.getOrElse("(no result)")}. Acknowledge this to the user."
            loop.run(channel, from, note, None, turnId)
        }
    }
  }
}
