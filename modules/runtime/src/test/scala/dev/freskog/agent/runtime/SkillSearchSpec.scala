package dev.freskog.agent.runtime

import zio._
import zio.test._

object SkillSearchSpec extends ZIOSpecDefault {

  private val skills = List(
    Skill(
      name = "safe-terminal",
      description = "Execute shell commands safely with bounded output.",
      version = None,
      capabilities = Nil,
      path = "/skills/safe-terminal/SKILL.md",
      body = "Use safe-run to invoke commands. Output is captured and trimmed."
    ),
    Skill(
      name = "commitments",
      description = "Track obligations for people.",
      version = None,
      capabilities = Nil,
      path = "/skills/commitments/SKILL.md",
      body = "Propose commitments via the person CLI. Commitments persist."
    ),
    Skill(
      name = "inbox-triage",
      description = "Process email and surface action items.",
      version = None,
      capabilities = Nil,
      path = "/skills/inbox-triage/SKILL.md",
      body = "Read messages, extract obligations, and propose commitments."
    )
  )

  def spec = suite("SkillSearchSpec")(
    test("ranks shell-execution skill first for a shell-execution query") {
      SkillSearch.search(skills, "execute a shell command", 5).map { hits =>
        assertTrue(hits.headOption.exists(_.name == "safe-terminal"))
      }
    },
    test("ranks commitments skill first for an obligation query") {
      SkillSearch.search(skills, "track an obligation", 5).map { hits =>
        assertTrue(hits.headOption.exists(_.name == "commitments"))
      }
    },
    test("returns empty for unrelated query terms") {
      SkillSearch.search(skills, "wholly unrelated mythology", 5).map { hits =>
        assertTrue(hits.isEmpty)
      }
    },
    test("returns empty for blank query") {
      SkillSearch.search(skills, "   ", 5).map(hits => assertTrue(hits.isEmpty))
    },
    test("sanitizes punctuation-heavy queries safely") {
      // Should not throw; should still match.
      SkillSearch.search(skills, "shell: \"command\"!", 5).map { hits =>
        assertTrue(hits.headOption.exists(_.name == "safe-terminal"))
      }
    }
  )
}
