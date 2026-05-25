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
        _  <- ZIO.when(doSeed)(SeedData.seed(db).catchAll(e => ZIO.logWarning(s"Seed skipped: ${e.getMessage}")))

        service = PersonService.live(
          Repos.sqlitePersonRepo(db),
          Repos.sqliteScopeRepo(db),
          Repos.sqliteScopeRoleRepo(db),
          Repos.sqliteCommitmentRepo(db),
          Repos.sqliteMemoryRepo(db),
          Repos.sqliteApprovalRepo(db),
          Repos.sqliteAuditRepo(db)
        )

        routes = Routes.make(service)
        _ <- Server.serve(routes).provide(Server.defaultWith(_.binding("127.0.0.1", port)))
      } yield ()
    }
  }
}
