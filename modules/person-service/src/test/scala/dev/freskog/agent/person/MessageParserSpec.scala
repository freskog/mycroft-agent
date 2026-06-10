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
