package dev.freskog.agent.mycroft.tools

import dev.freskog.agent.common.AgentError
import dev.freskog.agent.runtime.{Skill, SkillCatalog, SkillError, SkillHit, SkillSearch}

import zio._

import java.nio.file.Path

/** Loads and searches the SKILL.md catalogue for the harness. Wraps the
 *  `runtime` module's `SkillCatalog` / `SkillSearch` so the rest of mycroft
 *  depends on a small typed surface (and on `AgentError`, not `SkillError`). */
trait SkillProvider {
  def find(name: String): IO[AgentError, Option[Skill]]
  def search(query: String, limit: Int): IO[AgentError, List[SkillHit]]
}

object SkillProvider {

  def live(skillsDir: Path): SkillProvider = new SkillProvider {

    def find(name: String): IO[AgentError, Option[Skill]] =
      SkillCatalog.findByName(skillsDir, name).map(Some(_))
        .catchSome { case SkillError.NotFound(_) => ZIO.none }
        .mapError(translate)

    def search(query: String, limit: Int): IO[AgentError, List[SkillHit]] =
      SkillCatalog.scan(skillsDir)
        .flatMap(skills => SkillSearch.search(skills, query, limit))
        .mapError(translate)
        // A missing skills dir is not fatal for routing — just yield no candidates.
        .catchSome { case _: AgentError => ZIO.succeed(Nil) }
  }

  private def translate(e: SkillError): AgentError = e match {
    case SkillError.SkillsDirMissing(p)   => AgentError.Validation(s"skills dir missing: $p")
    case SkillError.MalformedSkill(p, msg) => AgentError.Validation(s"malformed skill $p: $msg")
    case SkillError.NotFound(name)        => AgentError.NotFound("skill", name)
    case SkillError.IoError(msg)          => AgentError.Persistence(s"skill io: $msg")
  }
}
