package dev.freskog.agent.person

import dev.freskog.agent.common.PersonId
import dev.freskog.agent.person.calendar.CalendarClient
import dev.freskog.agent.person.domain.CalendarCreateEventRequest

import zio.test._
import zio.json._

import java.time.Instant

object CalendarClientSpec extends ZIOSpecDefault {

  private def req(allDay: Boolean = false, loc: Option[String] = None, desc: Option[String] = None) =
    CalendarCreateEventRequest(
      ownerPersonId = PersonId("fred"),
      summary       = "Parents' evening",
      start         = Instant.parse("2026-06-20T17:00:00Z"),
      end           = Instant.parse("2026-06-20T18:00:00Z"),
      allDay        = allDay,
      location      = loc,
      description   = desc
    )

  def spec = suite("CalendarClientSpec")(

    test("CalendarCreateEventRequest round-trips through JSON (the approval payload)") {
      val r       = req(loc = Some("St Kilians"), desc = Some("from newsletter"))
      val decoded = r.toJson.fromJson[CalendarCreateEventRequest]
      assertTrue(decoded == Right(r))
    },

    test("buildEventBody emits dateTime for a timed event, with optional fields") {
      val body = CalendarClient.buildEventBody(req(loc = Some("St Kilians"), desc = Some("d")))
      val s    = body.toJson
      assertTrue(
        s.contains("\"summary\":\"Parents' evening\""),
        s.contains("\"start\":{\"dateTime\":\"2026-06-20T17:00:00Z\"}"),
        s.contains("\"end\":{\"dateTime\":\"2026-06-20T18:00:00Z\"}"),
        s.contains("\"location\":\"St Kilians\""),
        s.contains("\"description\":\"d\""),
        !s.contains("\"date\":")
      )
    },

    test("buildEventBody emits date (not dateTime) for an all-day event; omits absent optionals") {
      val body = CalendarClient.buildEventBody(req(allDay = true))
      val s    = body.toJson
      assertTrue(
        s.contains("\"start\":{\"date\":\"2026-06-20\"}"),
        s.contains("\"end\":{\"date\":\"2026-06-20\"}"),
        !s.contains("dateTime"),
        !s.contains("location"),
        !s.contains("description")
      )
    }
  )
}
