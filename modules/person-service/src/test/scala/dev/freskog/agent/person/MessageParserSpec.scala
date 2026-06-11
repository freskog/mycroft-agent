package dev.freskog.agent.person

import dev.freskog.agent.person.gmail.MessageParser

import zio.test._
import zio.json._
import zio.json.ast.Json

object MessageParserSpec extends ZIOSpecDefault {

  private val samplePayload =
    """{
      |  "id": "msg123",
      |  "threadId": "thread456",
      |  "internalDate": "1717000000000",
      |  "payload": {
      |    "mimeType": "multipart/alternative",
      |    "headers": [
      |      {"name": "From", "value": "Sarah <sarah@example.com>"},
      |      {"name": "Subject", "value": "Q3 report review"}
      |    ],
      |    "parts": [
      |      {
      |        "mimeType": "text/plain",
      |        "body": {"data": "Q2FuIHlvdSByZXZpZXcgYnkgRU9EPw=="}
      |      }
      |    ]
      |  }
      |}""".stripMargin

  private val payloadWithAttachment =
    """{
      |  "id": "msg789",
      |  "internalDate": "1717000000000",
      |  "payload": {
      |    "mimeType": "multipart/mixed",
      |    "headers": [
      |      {"name": "From", "value": "School <office@school.edu>"},
      |      {"name": "Subject", "value": "Permission slip"}
      |    ],
      |    "parts": [
      |      {
      |        "mimeType": "text/plain",
      |        "body": {"data": "U2VlIGF0dGFjaGVkIFBERg=="}
      |      },
      |      {
      |        "mimeType": "application/pdf",
      |        "filename": "permission-slip.pdf",
      |        "body": {"attachmentId": "ATTACH-abc-123", "size": 20480}
      |      }
      |    ]
      |  }
      |}""".stripMargin

  def spec = suite("MessageParserSpec")(
    test("parses Gmail message with plain text part") {
      val json = samplePayload.fromJson[Json].toOption.get
      val parsed = MessageParser.parseMessage(json)
      assertTrue(
        parsed.isDefined,
        parsed.get.id == "msg123",
        parsed.get.from.contains("sarah@example.com"),
        parsed.get.subject == "Q3 report review",
        parsed.get.bodyText.contains("review by EOD"),
        parsed.get.attachments.isEmpty
      )
    },
    test("strips invisible / format (Cf) characters from the body (anti-injection)") {
      // visible text interleaved with zero-width space, bidi override pair, and BOM,
      // built from code points (Scala 2.13 forbids literal bidi chars in source)
      val zwsp = 0x200b.toChar; val rlo = 0x202e.toChar; val pdf = 0x202c.toChar; val bom = 0xfeff.toChar
      val raw  = s"Pay the invoice$zwsp by Friday${rlo}evil$pdf$bom"
      val b64 = java.util.Base64.getUrlEncoder.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val json = s"""{"id":"m1","internalDate":"1","payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"x@y.com"},{"name":"Subject","value":"hi"}],"body":{"data":"$b64"}}}"""
        .fromJson[Json].toOption.get
      val parsed = MessageParser.parseMessage(json).get
      assertTrue(
        parsed.bodyText.contains("Pay the invoice"),
        parsed.bodyText.contains("by Friday"),
        // no Unicode format (Cf) chars survive — the injection vectors are gone
        !parsed.bodyText.exists(c => Character.getType(c) == Character.FORMAT.toInt)
      )
    },
    test("html-only body is reduced to readable text — CSS/script/comments/entities stripped") {
      val html =
        """<html><head><style>.email-container { font-family: Arial !important; padding: 0; }
          |@media screen { .x { display:none; } }</style></head>
          |<body><!-- hidden CSS note: margin:0 --><div>Parents&nbsp;evening is on
          |<strong>Thu&nbsp;20 June</strong>.</div><p>Please reply by Friday &amp; bring the form.</p>
          |<script>var x = 1;</script></body></html>""".stripMargin
      val b64 = java.util.Base64.getUrlEncoder.encodeToString(html.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val json = s"""{"id":"h1","internalDate":"1","payload":{"mimeType":"text/html","headers":[{"name":"From","value":"school@x.edu"},{"name":"Subject","value":"news"}],"body":{"data":"$b64"}}}"""
        .fromJson[Json].toOption.get
      val parsed = MessageParser.parseMessage(json).get
      assertTrue(
        parsed.bodyText.contains("Parents evening is on"),
        parsed.bodyText.contains("Thu 20 June"),
        parsed.bodyText.contains("reply by Friday & bring the form"),
        // none of the CSS / script / comment / tag noise survives
        !parsed.bodyText.contains("!important"),
        !parsed.bodyText.contains("font-family"),
        !parsed.bodyText.contains("{"),
        !parsed.bodyText.contains("@media"),
        !parsed.bodyText.contains("var x"),
        !parsed.bodyText.contains("hidden CSS note"),
        !parsed.bodyText.contains("&nbsp;"),
        !parsed.bodyText.contains("<")
      )
    },
    test("strips HTML even when it arrives inside a text/plain part (CSS-soup guard)") {
      // Some senders put full HTML (with CSS + MS-Outlook conditional comments) in
      // the text/plain part; it must still be reduced to readable text.
      val htmlInPlain =
        """<!--[if mso 9]><style>.x{font-family:Arial !important}</style><![endif]-->
          |<div>Reservation&zwnj; confirmed for <b>20 June</b>.</div>""".stripMargin
      val b64 = java.util.Base64.getUrlEncoder.encodeToString(htmlInPlain.getBytes("UTF-8"))
      val json = s"""{"id":"tp1","internalDate":"1","payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"hotel@x.com"},{"name":"Subject","value":"res"}],"body":{"data":"$b64"}}}"""
        .fromJson[Json].toOption.get
      val parsed = MessageParser.parseMessage(json).get
      assertTrue(
        parsed.bodyText.contains("Reservation confirmed for"),
        parsed.bodyText.contains("20 June"),
        !parsed.bodyText.contains("!important"),
        !parsed.bodyText.contains("font-family"),
        !parsed.bodyText.contains("[if mso"),
        !parsed.bodyText.contains("<")
      )
    },
    test("genuine plain text is passed through unchanged") {
      val plain = "Can you review the deck by Friday? Thanks — Graham"
      val b64 = java.util.Base64.getUrlEncoder.encodeToString(plain.getBytes("UTF-8"))
      val json = s"""{"id":"pt1","internalDate":"1","payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"g@x.com"},{"name":"Subject","value":"deck"}],"body":{"data":"$b64"}}}"""
        .fromJson[Json].toOption.get
      assertTrue(MessageParser.parseMessage(json).get.bodyText == plain)
    },
    test("prefers the text/plain part over text/html in multipart/alternative") {
      val html = "<style>.a{x:1}</style><p>HTML version</p>"
      val plain = "Plain version — read me"
      val hB64 = java.util.Base64.getUrlEncoder.encodeToString(html.getBytes("UTF-8"))
      val pB64 = java.util.Base64.getUrlEncoder.encodeToString(plain.getBytes("UTF-8"))
      val json = s"""{"id":"mp1","internalDate":"1","payload":{"mimeType":"multipart/alternative","headers":[{"name":"From","value":"a@b.com"},{"name":"Subject","value":"s"}],"parts":[{"mimeType":"text/plain","body":{"data":"$pB64"}},{"mimeType":"text/html","body":{"data":"$hB64"}}]}}"""
        .fromJson[Json].toOption.get
      val parsed = MessageParser.parseMessage(json).get
      assertTrue(parsed.bodyText.contains("Plain version"), !parsed.bodyText.contains("HTML version"))
    },
    test("extracts attachment metadata and still reads the body") {
      val json   = payloadWithAttachment.fromJson[Json].toOption.get
      val parsed = MessageParser.parseMessage(json).get
      assertTrue(
        parsed.bodyText.contains("See attached PDF"),
        parsed.attachments.size == 1,
        parsed.attachments.head.attachmentId == "ATTACH-abc-123",
        parsed.attachments.head.filename == "permission-slip.pdf",
        parsed.attachments.head.mimeType == "application/pdf",
        parsed.attachments.head.sizeBytes == 20480L
      )
    }
  )
}
