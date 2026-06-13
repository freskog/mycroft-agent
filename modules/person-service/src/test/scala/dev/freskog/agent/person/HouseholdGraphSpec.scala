package dev.freskog.agent.person

import dev.freskog.agent.common._
import dev.freskog.agent.person.domain._
import dev.freskog.agent.person.persistence._
import dev.freskog.agent.person.seed.SeedData
import dev.freskog.agent.person.seed.SeedData.FredId
import dev.freskog.agent.person.service.PersonService

import zio._
import zio.test._

import java.time.Instant

/** Repo- and service-level coverage for the person/entity/relationship graph:
 *  entity + relationship CRUD, propose→accept lifecycle, supersession, the
 *  as-of validity window on edges, and the assembled HouseholdGraph. */
object HouseholdGraphSpec extends ZIOSpecDefault {

  private def withRepos(
    f: (EntityRepo, RelationshipRepo) => ZIO[Any, AgentError, TestResult]
  ): ZIO[Any, AgentError, TestResult] =
    ZIO.scoped {
      for {
        db     <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
        _      <- Migrations.migrate(db)
        result <- f(Repos.sqliteEntityRepo(db), Repos.sqliteRelationshipRepo(db))
      } yield result
    }

  private def withService(
    f: PersonService => ZIO[Any, AgentError, TestResult]
  ): ZIO[Any, AgentError, TestResult] =
    ZIO.scoped {
      for {
        db      <- Sqlite.live(":memory:").build.map(_.get[Sqlite])
        _       <- Migrations.migrate(db)
        _       <- SeedData.seed(db)
        hub     <- Hub.sliding[ApprovalEvent](16)
        service  = PersonService.live(
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
          hub
        )
        result  <- f(service)
      } yield result
    }

  private def entity(id: String, name: String, kind: EntityKind, status: MemoryStatus, now: Instant): Entity =
    Entity(EntityId(id), kind, name, None, status, "onboarding:test", Some(0.9), None, now, now)

  private def rel(
    id: String, fromId: String, toId: String, relType: String, status: MemoryStatus, now: Instant,
    validFrom: Option[Instant] = None, validUntil: Option[Instant] = None
  ): Relationship =
    Relationship(RelationshipId(id), fromId, NodeKind.Person, relType, toId, NodeKind.Entity,
                 status, "onboarding:test", Some(0.9), None, None, validFrom, validUntil, now, now)

  def spec: Spec[Any, AgentError] = suite("HouseholdGraphSpec")(

    // --- EntityRepo ---

    test("entity create + findById round-trip") {
      withRepos { (entities, _) =>
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val e   = entity("e1", "MegaCorp", EntityKind.Organization, MemoryStatus.Proposed, now)
        for {
          _     <- entities.create(e)
          found <- entities.findById(EntityId("e1"))
        } yield assertTrue(found.contains(e))
      }
    },
    test("entity findAll filters by kind and status") {
      withRepos { (entities, _) =>
        val now = Instant.parse("2026-01-01T00:00:00Z")
        for {
          _       <- entities.create(entity("e1", "MegaCorp", EntityKind.Organization, MemoryStatus.Accepted, now))
          _       <- entities.create(entity("e2", "Oakwood", EntityKind.School, MemoryStatus.Accepted, now))
          _       <- entities.create(entity("e3", "Draft Co", EntityKind.Organization, MemoryStatus.Proposed, now))
          orgs    <- entities.findAll(Some("organization"), None)
          schools <- entities.findAll(Some("school"), None)
          accepted<- entities.findAll(None, Some("accepted"))
        } yield assertTrue(
          orgs.map(_.id.value).toSet == Set("e1", "e3"),
          schools.map(_.id.value) == List("e2"),
          accepted.map(_.id.value).toSet == Set("e1", "e2")
        )
      }
    },
    test("entity findByName is case-insensitive substring match") {
      withRepos { (entities, _) =>
        val now = Instant.parse("2026-01-01T00:00:00Z")
        for {
          _    <- entities.create(entity("e1", "MegaCorp Industries", EntityKind.Organization, MemoryStatus.Accepted, now))
          hits <- entities.findByName("megacorp", Some("accepted"))
          miss <- entities.findByName("nonsense", Some("accepted"))
        } yield assertTrue(hits.map(_.id.value) == List("e1"), miss.isEmpty)
      }
    },
    test("entity supersede sets superseded_by_id on the old node") {
      withRepos { (entities, _) =>
        val now = Instant.parse("2026-01-01T00:00:00Z")
        for {
          _   <- entities.create(entity("old", "Acme", EntityKind.Organization, MemoryStatus.Accepted, now))
          _   <- entities.create(entity("new", "Beta Corp", EntityKind.Organization, MemoryStatus.Accepted, now))
          _   <- entities.setSupersededBy(EntityId("old"), EntityId("new"), now)
          old <- entities.findById(EntityId("old"))
        } yield assertTrue(old.flatMap(_.supersededById).contains(EntityId("new")))
      }
    },

    // --- RelationshipRepo ---

    test("relationship create + findById round-trip") {
      withRepos { (_, rels) =>
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val r   = rel("r1", "fred", "e1", RelationshipType.EmployedBy, MemoryStatus.Accepted, now)
        for {
          _     <- rels.create(r)
          found <- rels.findById(RelationshipId("r1"))
        } yield assertTrue(found.contains(r))
      }
    },
    test("relationship findAll filters by from/rel_type and excludes superseded") {
      withRepos { (_, rels) =>
        val now = Instant.parse("2026-01-01T00:00:00Z")
        for {
          _        <- rels.create(rel("r1", "fred", "e1", RelationshipType.EmployedBy, MemoryStatus.Accepted, now))
          _        <- rels.create(rel("r2", "fred", "e2", RelationshipType.Attends, MemoryStatus.Accepted, now))
          _        <- rels.create(rel("r3", "fred", "e3", RelationshipType.EmployedBy, MemoryStatus.Accepted, now))
          _        <- rels.setSupersededBy(RelationshipId("r3"), RelationshipId("r1"), now)
          employed <- rels.findAll(Some("fred"), None, Some(RelationshipType.EmployedBy), Some("accepted"), None)
        } yield assertTrue(employed.map(_.id.value) == List("r1"))
      }
    },
    test("relationship as-of keeps only edges active in their validity window") {
      withRepos { (_, rels) =>
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val start = Instant.parse("2026-03-01T00:00:00Z")
        val end   = Instant.parse("2026-09-01T00:00:00Z")
        for {
          _      <- rels.create(rel("r1", "fred", "e1", RelationshipType.Attends, MemoryStatus.Accepted, now,
                                     validFrom = Some(start), validUntil = Some(end)))
          inside <- rels.findAll(None, None, None, None, Some(Instant.parse("2026-06-01T00:00:00Z")))
          before <- rels.findAll(None, None, None, None, Some(Instant.parse("2026-02-01T00:00:00Z")))
          after  <- rels.findAll(None, None, None, None, Some(Instant.parse("2026-10-01T00:00:00Z")))
        } yield assertTrue(
          inside.map(_.id.value) == List("r1"),
          before.isEmpty,
          after.isEmpty
        )
      }
    },

    // --- Service lifecycle + assembled graph ---

    test("propose entity is gateless — created accepted, nothing pending") {
      withService { svc =>
        for {
          e        <- svc.proposeEntity(ProposeEntityRequest(EntityKind.Organization, "MegaCorp", None, "onboarding:work", Some(0.9)))
          accepted <- svc.listEntities(None, Some("accepted"))
          proposed <- svc.listEntities(None, Some("proposed"))
        } yield assertTrue(
          e.status == MemoryStatus.Accepted,
          accepted.exists(_.id == e.id),
          proposed.isEmpty
        )
      }
    },
    test("resolveEntities returns only accepted matches") {
      withService { svc =>
        for {
          a <- svc.proposeEntity(ProposeEntityRequest(EntityKind.Organization, "MegaCorp", None, "onboarding:work", Some(0.9)))
          _ <- svc.proposeEntity(ProposeEntityRequest(EntityKind.Organization, "MegaCorp Holdings", None, "chat", Some(0.5)))
          hits <- svc.resolveEntities("megacorp")
        } yield assertTrue(
          hits.exists(_.id == a.id),
          hits.forall(_.status == MemoryStatus.Accepted)
        )
      }
    },
    test("household returns accepted entities and active relationships") {
      withService { svc =>
        for {
          e   <- svc.proposeEntity(ProposeEntityRequest(EntityKind.Organization, "MegaCorp", None, "onboarding:work", Some(0.9)))
          r   <- svc.proposeRelationship(ProposeRelationshipRequest(
                   FredId.value, NodeKind.Person, RelationshipType.EmployedBy, e.id.value, NodeKind.Entity,
                   "onboarding:work", Some(0.9)
                 ))
          g   <- svc.household
        } yield assertTrue(
          g.entities.exists(_.id == e.id),
          g.relationships.exists(_.id == r.id),
          g.relationships.forall(_.status == MemoryStatus.Accepted)
        )
      }
    },
    test("household omits rejected entities and relationships (post-hoc correction)") {
      withService { svc =>
        for {
          e <- svc.proposeEntity(ProposeEntityRequest(EntityKind.School, "Oakwood", None, "chat", Some(0.4)))
          r <- svc.proposeRelationship(ProposeRelationshipRequest(
                 FredId.value, NodeKind.Person, RelationshipType.Attends, e.id.value, NodeKind.Entity, "chat", Some(0.4)
               ))
          _ <- svc.rejectRelationship(r.id, Some("misheard"))
          _ <- svc.rejectEntity(e.id, Some("misheard"))
          g <- svc.household
        } yield assertTrue(
          !g.entities.exists(_.id == e.id),
          !g.relationships.exists(_.id == r.id)
        )
      }
    },
    test("relationship transition: close old (validUntil) + open new (validFrom)") {
      withService { svc =>
        val cutover = Instant.parse("2026-09-01T00:00:00Z")
        for {
          school1 <- svc.proposeEntity(ProposeEntityRequest(EntityKind.School, "Oakwood Primary", None, "onboarding:kids", Some(0.9)))
          school2 <- svc.proposeEntity(ProposeEntityRequest(EntityKind.School, "Hillside Secondary", None, "onboarding:kids", Some(0.9)))
          oldEdge <- svc.proposeRelationship(ProposeRelationshipRequest(
                       "child-1", NodeKind.Person, RelationshipType.Attends, school1.id.value, NodeKind.Entity,
                       "onboarding:kids", Some(0.9), validUntil = Some(cutover)
                     ))
          newEdge <- svc.proposeRelationship(ProposeRelationshipRequest(
                       "child-1", NodeKind.Person, RelationshipType.Attends, school2.id.value, NodeKind.Entity,
                       "onboarding:kids", Some(0.9), validFrom = Some(cutover)
                     ))
          beforeCut <- svc.listRelationships(Some("child-1"), None, Some(RelationshipType.Attends), Some("accepted"),
                                             Some(Instant.parse("2026-06-01T00:00:00Z")))
          afterCut  <- svc.listRelationships(Some("child-1"), None, Some(RelationshipType.Attends), Some("accepted"),
                                             Some(Instant.parse("2026-12-01T00:00:00Z")))
        } yield assertTrue(
          beforeCut.map(_.id) == List(oldEdge.id),
          afterCut.map(_.id) == List(newEdge.id)
        )
      }
    }
  )
}
