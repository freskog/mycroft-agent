package dev.freskog.agent.mycroft

import dev.freskog.agent.common._
import dev.freskog.agent.mycroft.agent.Prompt

import zio.test._

import java.time.{Instant, ZoneId}

object PromptSpec extends ZIOSpecDefault {

  private val now  = Instant.parse("2026-06-07T09:00:00Z")
  private val zone = ZoneId.of("Europe/London")

  private def fact(text: String): MemoryItem =
    MemoryItem(
      id = MemoryId("m-" + text.hashCode.toString),
      personId = Some(PersonId("fred")),
      status = MemoryStatus.Accepted,
      kind = MemoryKind.Fact,
      text = text,
      source = "onboarding:profile",
      confidence = Some(0.9),
      createdAt = now,
      updatedAt = now
    )

  private def entity(name: String, kind: EntityKind): Entity =
    Entity(EntityId("e-" + name), kind, name, None, MemoryStatus.Accepted, "onboarding:test", Some(0.9), None, now, now)

  private def rel(fromId: String, relType: String, toId: String): Relationship =
    Relationship(RelationshipId("r-" + fromId + toId), fromId, NodeKind.Person, relType, toId, NodeKind.Entity,
                 MemoryStatus.Accepted, "onboarding:test", Some(0.9), None, None, None, None, now, now)

  def spec = suite("PromptSpec")(

    test("empty profile + empty graph renders the gentle onboarding nudge") {
      val s = Prompt.system(
        sender  = PersonId("fred"),
        bundle  = ContextBundle(Nil, Nil),
        profile = Nil,
        graph   = HouseholdGraph(Nil, Nil),
        now     = now,
        zone    = zone
      )
      assertTrue(
        s.contains("Household / Owner profile:"),
        s.contains("offer to set this up via the onboarding skill"),
        s.contains("Sender: fred")
      )
    },

    test("populated profile renders facts, entities and relationships, no nudge") {
      val graph = HouseholdGraph(
        entities      = List(entity("MegaCorp", EntityKind.Organization), entity("Oakwood Primary", EntityKind.School)),
        relationships = List(rel("fred", RelationshipType.EmployedBy, "e-MegaCorp"))
      )
      val s = Prompt.system(
        sender  = PersonId("fred"),
        bundle  = ContextBundle(Nil, Nil),
        profile = List(fact("Fred lives in London"), fact("Has two children")),
        graph   = graph,
        now     = now,
        zone    = zone
      )
      assertTrue(
        s.contains("Fred lives in London"),
        s.contains("Has two children"),
        s.contains("MegaCorp (organization)"),
        s.contains("Oakwood Primary (school)"),
        s.contains("fred —employed_by→ e-MegaCorp"),
        !s.contains("offer to set this up via the onboarding skill")
      )
    },

    test("recent context renders facts and events; clock line resolves relative dates") {
      val bundle = ContextBundle(
        facts  = List(MemoryHit(fact("Prefers morning standups"), 0.8)),
        events = List(AuditEvent(EventId("ev1"), "agent", "obs.cal", EventCategory.Observation,
                                 "observation", None, Some("Calendar empty Friday"), "{}", now))
      )
      val s = Prompt.system(PersonId("fred"), bundle, Nil, HouseholdGraph(Nil, Nil), now, zone)
      assertTrue(
        s.contains("Recent context:"),
        s.contains("Prefers morning standups"),
        s.contains("Calendar empty Friday"),
        s.contains("Current date & time:")
      )
    }
  )
}
