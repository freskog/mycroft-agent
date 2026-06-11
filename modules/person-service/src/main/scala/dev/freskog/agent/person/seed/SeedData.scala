package dev.freskog.agent.person.seed

import dev.freskog.agent.common._
import dev.freskog.agent.person.persistence._

import zio._

object SeedData {

  // Canonical seeded ids — used by both the seeder and tests.
  val FredId: PersonId   = PersonId("fred")
  val PaulaId: PersonId  = PersonId("paula")

  val SampleGoalId: GoalId = GoalId("goal-seed-q3-report")

  def seed(db: Sqlite): IO[AgentError, Unit] = {
    val personRepo = Repos.sqlitePersonRepo(db)
    val goalRepo   = Repos.sqliteGoalRepo(db)

    val persons = List(
      Person(FredId, "Fred", "Europe/Dublin", Some("en-IE"), active = true),
      Person(PaulaId, "Paula", "Europe/Dublin", None, active = true)
    )

    for {
      _   <- ZIO.foreach(persons)(personRepo.create)
      now <- Clock.instant
      sampleGoal = Goal(
        id = SampleGoalId,
        ownerPersonId = FredId,
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
