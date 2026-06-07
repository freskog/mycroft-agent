package dev.freskog.agent.mycroft.agent

import dev.freskog.agent.mycroft.tools.ToolRegistry

import zio.Chunk
import zio.json._
import zio.json.ast.Json

/** The `run_skill` control-plane tool: it composes skills (runs one as an
 *  isolated sub-task) but never touches the OS, so the OS surface stays
 *  `safe_run` + `runlog`. This object also assembles the full tools array the
 *  model sees (OS tools + run_skill). */
object SkillTools {

  val runSkillSpecJson: String =
    """{
      |  "type": "function",
      |  "function": {
      |    "name": "run_skill",
      |    "description": "Run a skill as an isolated sub-task and get back a structured result summary. Use this to compose skills — e.g. after classifying an email as 'school', run the school-events skill. The sub-task gets its own context and budget; only its summary returns to you, so its internal steps never clutter your reasoning.",
      |    "parameters": {
      |      "type": "object",
      |      "properties": {
      |        "name":   { "type": "string", "description": "The skill name, as listed by `skill list` / `skill search`." },
      |        "task":   { "type": "string", "description": "What this run should accomplish, in one or two sentences (the 'why' the sub-task keeps in view)." },
      |        "params": { "type": "string", "description": "Optional extra inputs for the skill (free-form text or JSON)." }
      |      },
      |      "required": ["name", "task"]
      |    }
      |  }
      |}""".stripMargin

  /** OS tools (safe_run + runlog) plus the run_skill control-plane tool, as a
   *  single JSON array string for the chat request. */
  val allToolsJson: String = {
    val os    = ToolRegistry.toolsJson.fromJson[Json].toOption.collect { case Json.Arr(es) => es }.getOrElse(Chunk.empty)
    val skill = runSkillSpecJson.fromJson[Json].toOption
    Json.Arr(os ++ Chunk.fromIterable(skill)).toJson
  }

  val ToolName: String = "run_skill"
}
