package dev.freskog.agent.person

import dev.freskog.agent.person.api.Routes
import dev.freskog.agent.person.persistence._
import dev.freskog.agent.person.seed.SeedData
import dev.freskog.agent.person.service.PersonService

import zio._
import zio.http._

import java.nio.file.{Files, Paths}

object Main extends ZIOAppDefault {

  private val defaultDbPath = {
    val home = java.lang.System.getProperty("user.home")
    s"$home/.local/state/person-service/person.sqlite"
  }

  def run: ZIO[Any, Any, Any] = {
    val dbPath = sys.env.getOrElse("PERSON_SERVICE_DB", defaultDbPath)
    val port   = sys.env.getOrElse("PERSON_SERVICE_PORT", "8080").toInt
    val host   = sys.env.getOrElse("PERSON_SERVICE_HOST", "0.0.0.0")
    val doSeed = sys.env.getOrElse("PERSON_SERVICE_SEED", "false").toLowerCase == "true"
    // The approval-decision endpoint is served only on this private interface, on
    // a network the agent cannot route to. If unset (local dev), it is folded into
    // the public server instead.
    val privateHost = sys.env.get("PERSON_SERVICE_PRIVATE_HOST").filter(_.nonEmpty)
    val privatePort = sys.env.getOrElse("PERSON_SERVICE_PRIVATE_PORT", "8090").toInt
    // Generous so an overnight async approval (e.g. a goal inferred by a nightly
    // inbox sync) is still decidable in the morning.
    val codeTtl     = java.time.Duration.ofHours(sys.env.getOrElse("APPROVAL_CODE_TTL_HOURS", "48").toLong)

    val program = for {
      _ <- ZIO.attemptBlocking {
        val parent = Paths.get(dbPath).getParent
        if (parent != null) Files.createDirectories(parent)
      }
      _ <- ZIO.logInfo(s"Starting person-service on port $port, db: $dbPath")
    } yield ()

    program *> ZIO.scoped {
      for {
        db <- Sqlite.live(dbPath).build.map(_.get[Sqlite])
        _  <- Migrations.migrate(db)
        _  <- ZIO.when(doSeed)(SeedData.seed(db).catchAll(e => ZIO.logWarning(s"Seed skipped: ${e.message}")))

        approvalHub <- Hub.sliding[dev.freskog.agent.common.ApprovalEvent](256)

        service = PersonService.live(
          Repos.sqlitePersonRepo(db),
          Repos.sqliteCommitmentRepo(db),
          Repos.sqliteMemoryRepo(db),
          Repos.sqliteApprovalRepo(db),
          Repos.sqliteAuditRepo(db),
          Repos.sqliteGoalRepo(db),
          Repos.sqliteGoalEvidenceRepo(db),
          Repos.sqliteEntityRepo(db),
          Repos.sqliteRelationshipRepo(db),
          Repos.sqliteChannelRepo(db),
          Repos.sqliteChannelMemberRepo(db),
          Repos.sqliteMessageRepo(db),
          Repos.sqliteCredentialRepo(db),
          Repos.sqliteInboxMessageRepo(db),
          Repos.sqliteCalendarEventRepo(db),
          Repos.sqliteBriefingRepo(db),
          approvalHub,
          codeTtl
        )

        publicRoutes = Routes.make(service, approvalHub)
        decideRoutes = Routes.decideRoutes(service)
        _ <- privateHost match {
          case Some(ph) =>
            ZIO.logInfo(s"Serving approval-decision endpoint privately on $ph:$privatePort") *>
              Server.serve(publicRoutes).provide(Server.defaultWith(_.binding(host, port)))
                .zipPar(Server.serve(decideRoutes).provide(Server.defaultWith(_.binding(ph, privatePort))))
          case None =>
            ZIO.logWarning("PERSON_SERVICE_PRIVATE_HOST unset — serving the decision endpoint on the public interface (dev only; the agent can reach it)") *>
              Server.serve(publicRoutes ++ decideRoutes).provide(Server.defaultWith(_.binding(host, port)))
        }
      } yield ()
    }
  }
}
