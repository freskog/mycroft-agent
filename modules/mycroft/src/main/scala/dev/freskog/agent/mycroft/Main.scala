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
        _ <- Server.serve(routes).provide(
          Server.defaultWith(_.binding(config.host, config.port).enableRequestStreaming)
        )
      } yield ()
    }
  }
}
