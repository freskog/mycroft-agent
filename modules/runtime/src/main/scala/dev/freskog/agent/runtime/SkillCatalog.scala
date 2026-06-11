package dev.freskog.agent.runtime

import zio._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

final case class Skill(
  name: String,
  description: String,
  version: Option[String],
  capabilities: List[String],
  path: String,
  body: String,
  // Optional reasoning mode for executing this skill: `direct` (thinking off,
  // fast) or `reason` (thinking on). Absent → the harness default applies.
  reasoning: Option[String] = None
)

sealed trait SkillError extends Product with Serializable
object SkillError {
  final case class SkillsDirMissing(path: String)            extends SkillError
  final case class MalformedSkill(path: String, msg: String) extends SkillError
  final case class NotFound(name: String)                    extends SkillError
  final case class IoError(msg: String)                      extends SkillError
}

object SkillCatalog {

  def scan(skillsDir: Path): IO[SkillError, List[Skill]] =
    ZIO.attemptBlocking {
      if (!Files.exists(skillsDir)) Left(SkillError.SkillsDirMissing(skillsDir.toString))
      else if (!Files.isDirectory(skillsDir)) Left(SkillError.SkillsDirMissing(skillsDir.toString))
      else {
        val stream = Files.list(skillsDir)
        try {
          val entries = stream.iterator().asScala.toList
          val files = entries.flatMap { entry =>
            val skillFile = entry.resolve("SKILL.md")
            if (Files.isDirectory(entry) && Files.exists(skillFile)) Some(skillFile)
            else None
          }
          Right(files)
        } finally stream.close()
      }
    }.mapError(e => SkillError.IoError(e.getMessage))
      .flatMap {
        case Left(err)    => ZIO.fail(err)
        case Right(files) =>
          ZIO.foreach(files)(loadSkill).map(_.sortBy(_.name))
      }

  def loadSkill(file: Path): IO[SkillError, Skill] =
    ZIO.attemptBlocking(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
      .mapError(e => SkillError.IoError(e.getMessage))
      .flatMap(content => ZIO.fromEither(parse(file, content)))

  def findByName(skillsDir: Path, name: String): IO[SkillError, Skill] =
    scan(skillsDir).flatMap { skills =>
      skills.find(_.name == name) match {
        case Some(s) => ZIO.succeed(s)
        case None    => ZIO.fail(SkillError.NotFound(name))
      }
    }

  private[runtime] def parse(file: Path, raw: String): Either[SkillError, Skill] = {
    val normalized = raw.replace("\r\n", "\n")
    val (fmRaw, body) =
      if (normalized.startsWith("---\n")) {
        val rest = normalized.drop(4)
        val end  = rest.indexOf("\n---\n")
        if (end < 0) return Left(SkillError.MalformedSkill(file.toString, "Frontmatter not terminated by '---'"))
        else (rest.substring(0, end), rest.substring(end + 5))
      } else {
        return Left(SkillError.MalformedSkill(file.toString, "SKILL.md must start with YAML frontmatter delimited by '---'"))
      }

    val fields = parseFrontmatter(fmRaw)
    for {
      name <- fields.get("name").map(_.trim).filter(_.nonEmpty)
                .toRight(SkillError.MalformedSkill(file.toString, "Missing required field: name"))
      description <- fields.get("description").map(_.trim).filter(_.nonEmpty)
                       .toRight(SkillError.MalformedSkill(file.toString, "Missing required field: description"))
    } yield Skill(
      name = name,
      description = description,
      version = fields.get("version").map(_.trim).filter(_.nonEmpty),
      capabilities = fields.get("capabilities").map(parseStringList).getOrElse(Nil),
      reasoning = fields.get("reasoning").map(_.trim).filter(_.nonEmpty),
      path = file.toString,
      body = body
    )
  }

  private[runtime] def parseFrontmatter(raw: String): Map[String, String] = {
    val lines = raw.split("\n", -1).toList
    val map = scala.collection.mutable.LinkedHashMap.empty[String, String]
    lines.foreach { line =>
      val trimmed = line.stripTrailing()
      if (trimmed.nonEmpty && !trimmed.startsWith("#")) {
        val idx = trimmed.indexOf(':')
        if (idx > 0) {
          val key   = trimmed.substring(0, idx).trim
          val value = trimmed.substring(idx + 1).trim
          if (key.nonEmpty) map(key) = stripQuotes(value)
        }
      }
    }
    map.toMap
  }

  private[runtime] def parseStringList(raw: String): List[String] = {
    val trimmed = raw.trim
    val inner =
      if (trimmed.startsWith("[") && trimmed.endsWith("]")) trimmed.substring(1, trimmed.length - 1)
      else trimmed
    inner.split(",").iterator
      .map(_.trim)
      .map(stripQuotes)
      .filter(_.nonEmpty)
      .toList
  }

  private def stripQuotes(s: String): String = {
    val t = s.trim
    if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))))
      t.substring(1, t.length - 1)
    else t
  }

  def resolveSkillsDir(explicit: Option[Path]): Path =
    explicit.getOrElse {
      val env = sys.env.get("RUNTIME_SKILLS_DIR").map(_.trim).filter(_.nonEmpty)
      Paths.get(env.getOrElse("./skills"))
    }
}
