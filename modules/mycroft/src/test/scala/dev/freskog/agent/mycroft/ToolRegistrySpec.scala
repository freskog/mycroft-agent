package dev.freskog.agent.mycroft

import dev.freskog.agent.mycroft.tools.ToolRegistry

import zio.test._

import java.nio.file.Paths

object ToolRegistrySpec extends ZIOSpecDefault {

  private val registry = ToolRegistry.live(Paths.get("/tmp"), defaultTimeout = 10)

  def spec = suite("ToolRegistry")(

    test("shell_run executes a command and reports success + output") {
      for {
        out <- registry.dispatch("shell_run", """{"command":"echo hello-mycroft"}""")
      } yield assertTrue(out.ok, out.content.contains("hello-mycroft"), out.content.contains("exit: 0"))
    },

    test("shell_run reports a non-zero exit as not ok") {
      for {
        out <- registry.dispatch("shell_run", """{"command":"exit 3"}""")
      } yield assertTrue(!out.ok, out.content.contains("exit: 3"))
    },

    test("missing command argument is rejected without running") {
      for {
        out <- registry.dispatch("shell_run", """{}""")
      } yield assertTrue(!out.ok, out.summary.contains("missing command"))
    },

    test("unknown tool name returns a descriptive non-ok outcome") {
      for {
        out <- registry.dispatch("person_memory_search", """{"query":"x"}""")
      } yield assertTrue(!out.ok, out.content.contains("no tool named"))
    }
  ) @@ TestAspect.withLiveClock
}
