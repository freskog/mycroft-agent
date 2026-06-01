package dev.freskog.agent.person.seed

import dev.freskog.agent.common.{Scope => PersonScope, _}
import dev.freskog.agent.person.persistence._

import zio._

object SeedData {

  // Canonical seeded ids — used by both the seeder and tests.
  val FredId: PersonId   = PersonId("fred")
  val PaulaId: PersonId  = PersonId("paula")

  val FredPrivate: ScopeId   = ScopeId("fred_private")
  val PaulaPrivate: ScopeId  = ScopeId("paula_private")
  val FamilyShared: ScopeId  = ScopeId("family_shared")
  val FredWork: ScopeId      = ScopeId("fred_work")

  val SampleGoalId: GoalId = GoalId("goal-seed-q3-report")

  def seed(db: Sqlite): IO[AgentError, Unit] = {
    val personRepo    = Repos.sqlitePersonRepo(db)
    val scopeRepo     = Repos.sqliteScopeRepo(db)
    val scopeRoleRepo = Repos.sqliteScopeRoleRepo(db)
    val goalRepo      = Repos.sqliteGoalRepo(db)

    val persons = List(
      Person(FredId, "Fred", "Europe/London", Some("en-GB"), active = true),
      Person(PaulaId, "Paula", "Europe/London", None, active = true)
    )

    val scopes = List(
      PersonScope(FredPrivate, "Fred Private", Some(FredId), ScopeKind.Private),
      PersonScope(PaulaPrivate, "Paula Private", Some(PaulaId), ScopeKind.Private),
      PersonScope(FamilyShared, "Family Shared", None, ScopeKind.Shared),
      PersonScope(FredWork, "Fred Work", Some(FredId), ScopeKind.Work)
    )

    val roles = List(
      PersonScopeRole(FredId, FredPrivate, ScopeRole.Owner),
      PersonScopeRole(FredId, FredWork, ScopeRole.Owner),
      PersonScopeRole(FredId, FamilyShared, ScopeRole.Owner),
      PersonScopeRole(PaulaId, PaulaPrivate, ScopeRole.Owner),
      PersonScopeRole(PaulaId, FamilyShared, ScopeRole.Owner)
    )

    for {
      _   <- ZIO.foreach(persons)(personRepo.create)
      _   <- ZIO.foreach(scopes)(scopeRepo.create)
      _   <- ZIO.foreach(roles)(scopeRoleRepo.create)
      now <- Clock.instant
      sampleGoal = Goal(
        id = SampleGoalId,
        ownerPersonId = FredId,
        scopeId = FredWork,
        title = "Approve Q3 report",
        outcome = "Q3 report committed to main branch and acknowledged by stakeholders.",
        evidenceRule = "Git commit hash on main referenced in evidence + stakeholder confirmation email.",
        constraintsJson = None,
        status = GoalStatus.Open,
        blockedReason = None,
        source = Some("seed"),
        createdAt = now,
        updatedAt = now
      )
      _ <- goalRepo.create(sampleGoal)
    } yield ()
  }
}
