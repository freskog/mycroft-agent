package dev.freskog.agent.runtime

import zio._
import zio.test._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object SkillCatalogSpec extends ZIOSpecDefault {

  private def withSkillsDir(skills: Map[String, String])(f: Path => ZIO[Any, Any, TestResult]): ZIO[Any, Any, TestResult] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking {
        val root = Files.createTempDirectory("skills-test")
        skills.foreach { case (name, content) =>
          val dir = Files.createDirectory(root.resolve(name))
          Files.write(dir.resolve("SKILL.md"), content.getBytes(StandardCharsets.UTF_8))
        }
        root
      }
    )(root =>
      ZIO.attemptBlocking {
        // best-effort recursive delete
        def del(p: Path): Unit = {
          if (Files.isDirectory(p)) {
            val s = Files.list(p)
            try s.iterator().forEachRemaining(del) finally s.close()
          }
          Files.deleteIfExists(p); ()
        }
        del(root)
      }.ignore
    )(f)

  private val validBody =
    """---
      |name: hello
      |description: A simple greeting skill.
      |version: 1.0.0
      |capabilities: [echo]
      |---
      |
      |# Hello
      |
      |Body content goes here.
      |""".stripMargin

  def spec = suite("SkillCatalogSpec")(
    test("parses a well-formed SKILL.md") {
      withSkillsDir(Map("hello" -> validBody)) { dir =>
        for {
          skills <- SkillCatalog.scan(dir)
        } yield assertTrue(
          skills.length == 1,
          skills.head.name == "hello",
          skills.head.description == "A simple greeting skill.",
          skills.head.version.contains("1.0.0"),
          skills.head.capabilities == List("echo"),
          skills.head.body.contains("Body content goes here")
        )
      }
    },
    test("rejects a skill missing required fields") {
      val badBody =
        """---
          |version: 0.1.0
          |---
          |
          |body
          |""".stripMargin
      withSkillsDir(Map("bad" -> badBody)) { dir =>
        SkillCatalog.scan(dir).either.map { result =>
          assertTrue(result.isLeft && result.swap.exists {
            case SkillError.MalformedSkill(_, msg) => msg.contains("name")
            case _ => false
          })
        }
      }
    },
    test("rejects unterminated frontmatter") {
      val badBody =
        """---
          |name: oops
          |description: no terminator
          |""".stripMargin
      withSkillsDir(Map("oops" -> badBody)) { dir =>
        SkillCatalog.scan(dir).either.map { result =>
          assertTrue(result.isLeft && result.swap.exists {
            case SkillError.MalformedSkill(_, msg) => msg.contains("not terminated")
            case _ => false
          })
        }
      }
    },
    test("returns SkillsDirMissing when directory does not exist") {
      val missing = java.nio.file.Paths.get("/tmp/definitely-not-here-" + java.util.UUID.randomUUID())
      SkillCatalog.scan(missing).either.map { result =>
        assertTrue(result.isLeft && result.swap.exists(_.isInstanceOf[SkillError.SkillsDirMissing]))
      }
    },
    test("findByName resolves an existing skill") {
      withSkillsDir(Map("hello" -> validBody)) { dir =>
        SkillCatalog.findByName(dir, "hello").map(s => assertTrue(s.name == "hello"))
      }
    },
    test("findByName fails on unknown") {
      withSkillsDir(Map("hello" -> validBody)) { dir =>
        SkillCatalog.findByName(dir, "missing").either.map { result =>
          assertTrue(result.isLeft && result.swap.exists(_.isInstanceOf[SkillError.NotFound]))
        }
      }
    },
    test("parseFrontmatter handles quoted strings and inline lists") {
      val fm =
        """name: "spaced name"
          |description: 'single quoted'
          |capabilities: [a, b, c]
          |""".stripMargin
      val parsed = SkillCatalog.parseFrontmatter(fm)
      assertTrue(
        parsed("name") == "spaced name",
        parsed("description") == "single quoted",
        SkillCatalog.parseStringList(parsed("capabilities")) == List("a", "b", "c")
      )
    }
  )
}
