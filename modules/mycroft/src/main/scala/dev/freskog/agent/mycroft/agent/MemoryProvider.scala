package dev.freskog.agent.mycroft.agent

import dev.freskog.agent.common._
import dev.freskog.agent.mycroft.tools.PersonClient

import zio._

/** Memory + household graph as a context-management concern, behind a narrow
 *  interface so the turn loop / skill executor depend on *recall* without
 *  binding to a concrete store. Reads (recall + task-relevant search + the
 *  pinned profile + the household graph) are the agent-facing surface;
 *  writes/consolidation are out of scope here — those are documented by the
 *  `memory` / `onboarding` skills and proposed through the `person` CLI like any
 *  other state.
 *
 *  `recall` is the generic decaying context bundle auto-injected every turn;
 *  `search` is the task-relevant retrieval a skill execution merges on top of
 *  it; `profile` is the non-decaying owner/household profile; `household` is the
 *  accepted person/entity/relationship graph. */
trait MemoryProvider {
  def recall(factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle]
  def search(query: String, person: Option[PersonId], limit: Int): IO[AgentError, List[MemoryHit]]
  def profile(limit: Int): IO[AgentError, List[MemoryItem]]
  def household: IO[AgentError, HouseholdGraph]
  /** Open goals to surface in the turn so the agent works toward them. */
  def openGoals: IO[AgentError, List[Goal]]
}

object MemoryProvider {

  /** Default implementation backed by person-service via the existing
   *  PersonClient. Every read is best-effort — a failure yields an empty result
   *  rather than aborting the turn. */
  def live(person: PersonClient): MemoryProvider = new MemoryProvider {

    def recall(factLimit: Int, eventLimit: Int): IO[AgentError, ContextBundle] =
      person.contextBundle(None, factLimit, eventLimit)
        .catchAll(_ => ZIO.succeed(ContextBundle(Nil, Nil)))

    def search(query: String, person0: Option[PersonId], limit: Int): IO[AgentError, List[MemoryHit]] =
      if (query.trim.isEmpty) ZIO.succeed(Nil)
      else person.searchMemory(query, person0, limit).catchAll(_ => ZIO.succeed(Nil))

    def profile(limit: Int): IO[AgentError, List[MemoryItem]] =
      person.profileFacts(limit).catchAll(_ => ZIO.succeed(Nil))

    val household: IO[AgentError, HouseholdGraph] =
      person.household.catchAll(_ => ZIO.succeed(HouseholdGraph(Nil, Nil)))

    val openGoals: IO[AgentError, List[Goal]] =
      person.openGoals.catchAll(_ => ZIO.succeed(Nil))
  }
}
