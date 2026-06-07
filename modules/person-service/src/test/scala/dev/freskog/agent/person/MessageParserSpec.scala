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
