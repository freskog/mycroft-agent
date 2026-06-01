package dev.freskog.agent.runtime

import dev.freskog.agent.common.JsonOutput

import zio._
import zio.cli._
import zio.json._

import java.nio.file.{Path, Paths}

object Main extends ZIOCliDefault {

  implicit val skillSummaryEncoder: JsonEncoder[SkillSummary] = DeriveJsonEncoder.gen[SkillSummary]
  implicit val skillHitEncoder: JsonEncoder[SkillHit]         = DeriveJsonEncoder.gen[SkillHit]

  // --- shared options ---

  private val skillsDirOption: Options[Option[Path]] =
    Options.directory("skills-dir").optional.map(_.map(p => Paths.get(p.toUri)))

  private val limitOption: Options[Int] =
    Options.integer("limit").withDefault(BigInt(10)).map(_.toInt)

  private val pathOnlyOption: Options[Boolean] =
    Options.boolean("path")

  private val queryArg: Args[String]    = Args.text("query")
  private val nameArg: Args[String]     = Args.text("name")

  // --- command shapes ---

  sealed trait Cmd extends Product with Serializable
  final case class ListCmd(skillsDir: Option[Path])                            extends Cmd
  final case class SearchCmd(skillsDir: Option[Path], query: String, limit: Int) extends Cmd
  final case class ShowCmd(skillsDir: Option[Path], name: String, pathOnly: Boolean) extends Cmd

  private val listCommand =
    Command("list", skillsDirOption).map(ListCmd)

  private val searchCommand =
    Command("search", skillsDirOption ++ limitOption, queryArg)
      .map { case ((dir, lim), q) => SearchCmd(dir, q, lim) }

  private val showCommand =
    Command("show", skillsDirOption ++ pathOnlyOption, nameArg)
      .map { case ((dir, pathOnly), n) => ShowCmd(dir, n, pathOnly) }

  private val skillCommand =
    Command("skill").subcommands(listCommand, searchCommand, showCommand)

  val cliApp = CliApp.make(
    name = "skill",
    version = "0.1.0",
    summary = HelpDoc.Span.text("Browse and search the agent's skill catalogue"),
    command = skillCommand
  ) { (cmd: Cmd) =>
    handle(cmd).catchAll {
      case SkillError.SkillsDirMissing(p)     => JsonOutput.error(s"Skills directory not found: $p")
      case SkillError.MalformedSkill(p, m)    => JsonOutput.error(s"Malformed skill at $p: $m")
      case SkillError.NotFound(n)             => JsonOutput.error(s"Skill not found: $n")
      case SkillError.IoError(m)              => JsonOutput.error(s"IO error: $m")
    }
  }

  private def handle(cmd: Cmd): IO[SkillError, Unit] = cmd match {
    case ListCmd(dir) =>
      val resolved = SkillCatalog.resolveSkillsDir(dir)
      SkillCatalog.scan(resolved).flatMap { skills =>
        val summaries = skills.map(s => SkillSummary(s.name, s.description, s.version, s.capabilities, s.path))
        JsonOutput.ok(summaries)
      }

    case SearchCmd(dir, query, limit) =>
      val resolved = SkillCatalog.resolveSkillsDir(dir)
      for {
        skills <- SkillCatalog.scan(resolved)
        hits   <- SkillSearch.search(skills, query, limit)
        _      <- JsonOutput.ok(hits)
      } yield ()

    case ShowCmd(dir, name, pathOnly) =>
      val resolved = SkillCatalog.resolveSkillsDir(dir)
      SkillCatalog.findByName(resolved, name).flatMap { skill =>
        if (pathOnly) Console.printLine(skill.path).orDie
        else Console.printLine(skill.body).orDie
      }
  }
}

final case class SkillSummary(
  name: String,
  description: String,
  version: Option[String],
  capabilities: List[String],
  path: String
)
