package dev.freskog.agent.person.seed

import dev.freskog.agent.common.{Scope => PersonScope, _}
import dev.freskog.agent.person.persistence._

import zio._

object SeedData {

  def seed(db: Sqlite): Task[Unit] = {
    val personRepo = Repos.sqlitePersonRepo(db)
    val scopeRepo = Repos.sqliteScopeRepo(db)
    val scopeRoleRepo = Repos.sqliteScopeRoleRepo(db)

    val persons = List(
      Person("fred", "Fred", "Europe/London", Some("en-GB"), active = true),
      Person("wife", "Wife", "Europe/London", None, active = true)
    )

    val scopes = List(
      PersonScope("fred_private", "Fred Private", Some("fred"), ScopeKind.Private),
      PersonScope("wife_private", "Wife Private", Some("wife"), ScopeKind.Private),
      PersonScope("family_shared", "Family Shared", None, ScopeKind.Shared),
      PersonScope("fred_work", "Fred Work", Some("fred"), ScopeKind.Work)
    )

    val roles = List(
      PersonScopeRole("fred", "fred_private", ScopeRole.Owner),
      PersonScopeRole("fred", "fred_work", ScopeRole.Owner),
      PersonScopeRole("fred", "family_shared", ScopeRole.Owner),
      PersonScopeRole("wife", "wife_private", ScopeRole.Owner),
      PersonScopeRole("wife", "family_shared", ScopeRole.Owner)
    )

    for {
      _ <- ZIO.foreach(persons)(personRepo.create)
      _ <- ZIO.foreach(scopes)(scopeRepo.create)
      _ <- ZIO.foreach(roles)(scopeRoleRepo.create)
    } yield ()
  }
}
