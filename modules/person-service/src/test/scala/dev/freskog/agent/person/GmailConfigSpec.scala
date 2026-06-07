package dev.freskog.agent.person

import dev.freskog.agent.person.gmail.GmailConfig

import zio.test._

object GmailConfigSpec extends ZIOSpecDefault {

  private val installedJson =
    """{
      |  "installed": {
      |    "client_id": "abc.apps.googleusercontent.com",
      |    "client_secret": "secret",
      |    "redirect_uris": ["http://localhost"]
      |  }
      |}""".stripMargin

  def spec = suite("GmailConfigSpec")(
    test("parse desktop (installed) client secret") {
      val result = GmailConfig.parseClientSecretJson(installedJson, GmailConfig.DefaultRedirectUri)
      assertTrue(
        result == Right(GmailConfig.Settings(
          "abc.apps.googleusercontent.com",
          "secret",
          GmailConfig.DefaultRedirectUri
        ))
      )
    },
    test("parse web client secret") {
      val json =
        """{"web":{"client_id":"x","client_secret":"y","redirect_uris":["http://localhost/cb"]}}"""
      val result = GmailConfig.parseClientSecretJson(json, "http://localhost/cb")
      assertTrue(result.map(_.clientId) == Right("x"))
    }
  )
}
