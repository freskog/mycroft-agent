package dev.freskog.agent.mycroft

import dev.freskog.agent.mycroft.tools.ToolRegistry

import zio.test._

import java.nio.file.Paths

object ToolRegistrySpec extends ZIOSpecDefault {

  private val registry = ToolRegistry.live(Paths.get("/tmp"), defaultTimeout = 10)

  // NOTE: tests that actually execute a command via safe_run are intentionally
  // omitted — safe_run shells out through `setsid`, which is absent on macOS
  // ("setsid not available in runtime image"), so they can only ever pass inside
  // the Linux dev container. The argument-validation cases below run no command
  // and therefore pass everywhere.
  def spec = suite("ToolRegistry")(

    test("missing command argument is rejected without running") {
      for {
        out <- registry.dispatch("safe_run", """{}""")
      } yield assertTrue(!out.ok, out.summary.contains("missing command"))
    },

    test("runlog with missing args is rejected without running") {
      for {
        out <- registry.dispatch("runlog", """{}""")
      } yield assertTrue(!out.ok, out.summary.contains("missing args"))
    },

    test("unknown tool name returns a descriptive non-ok outcome") {
      for {
        out <- registry.dispatch("person_memory_search", """{"query":"x"}""")
      } yield assertTrue(!out.ok, out.content.contains("no tool named"))
    }
  ) @@ TestAspect.withLiveClock
}
