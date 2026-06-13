package dev.freskog.agent.person

import dev.freskog.agent.person.delivery.Markdown

import zio.test._

object MarkdownSpec extends ZIOSpecDefault {

  def spec = suite("MarkdownSpec")(
    test("renders headings, bold, and bullet lists") {
      val html = Markdown.toHtml(
        """## Today
          |
          |- **All day** Liam's birthday
          |- 15:30 Guitar lesson
          |
          |Plain line.""".stripMargin
      )
      assertTrue(
        html.contains("<h3>Today</h3>"),
        html.contains("<ul>"),
        html.contains("<li><b>All day</b> Liam's birthday</li>"),
        html.contains("<li>15:30 Guitar lesson</li>"),
        html.contains("</ul>"),
        html.contains("<p>Plain line.</p>")
      )
    },
    test("escapes HTML special characters in content") {
      val html = Markdown.toHtml("- a < b & c > d")
      assertTrue(html.contains("a &lt; b &amp; c &gt; d"), !html.contains("a < b"))
    },
    test("UTF-8 content (€, em-dash, emoji) passes through unchanged") {
      val html = Markdown.toHtml("- Fees €14,274 — due 14 Jul 🎂")
      assertTrue(html.contains("€14,274 — due 14 Jul 🎂"))
    }
  )
}
