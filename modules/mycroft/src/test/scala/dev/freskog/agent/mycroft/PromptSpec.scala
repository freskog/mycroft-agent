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
        goals   = Nil,
        now     = now,
        zone    = zone
      )
      assertTrue(
        s.contains("Household / Owner profile:"),
        s.contains("offer to set this up via the onboarding skill"),
        s.contains("Sender: fred"),
        s.contains("Open goals"),
        s.contains("(none)")
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
        goals   = Nil,
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
      val s = Prompt.system(PersonId("fred"), bundle, Nil, HouseholdGraph(Nil, Nil), Nil, now, zone)
      assertTrue(
        s.contains("Recent context:"),
        s.contains("Prefers morning standups"),
        s.contains("Calendar empty Friday"),
        s.contains("Current date & time:")
      )
    },

    test("open goals render with title, outcome, due date and an overdue flag") {
      val goals = List(
        Goal(GoalId("g1"), PersonId("fred"), "Use CIS via extend", "CIS usable through extend",
             "demonstrated working use", None, GoalStatus.Open, None, Some("chat"), now, now,
             dueAt = Some(Instant.parse("2026-06-12T17:00:00Z"))),
        Goal(GoalId("g2"), PersonId("fred"), "Old task", "something", "rule", None,
             GoalStatus.Open, None, None, now, now,
             dueAt = Some(Instant.parse("2026-06-01T00:00:00Z")))   // before `now` → overdue
      )
      val s = Prompt.system(PersonId("fred"), ContextBundle(Nil, Nil), Nil, HouseholdGraph(Nil, Nil), goals, now, zone)
      assertTrue(
        s.contains("Use CIS via extend → CIS usable through extend"),
        s.contains("[due 2026-06-12]"),
        s.contains("OVERDUE"),
        !s.contains("(none)")
      )
    }
  )
}
