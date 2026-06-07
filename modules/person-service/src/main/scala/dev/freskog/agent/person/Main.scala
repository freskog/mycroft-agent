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
          Repos.sqliteInboxMessageRepo(db)
        )

        routes = Routes.make(service)
        _ <- Server.serve(routes).provide(Server.defaultWith(_.binding(host, port)))
      } yield ()
    }
  }
}
