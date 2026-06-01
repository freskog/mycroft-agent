package dev.freskog.agent.person.cli

import dev.freskog.agent.common.AgentError

import zio._

import java.net.URI
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}

object HttpClient {

  private val baseUrl: String =
    java.lang.System.getenv().getOrDefault("PERSON_SERVICE_URL", "http://127.0.0.1:8080")

  private val client: JHttpClient = JHttpClient.newHttpClient()

  def get(path: String, params: Map[String, String] = Map.empty): IO[AgentError, String] =
    send(buildGet(path, params), s"GET $path")

  def post(path: String, body: String): IO[AgentError, String] =
    send(buildPost(path, body), s"POST $path")

  private def buildGet(path: String, params: Map[String, String]): HttpRequest = {
    val query = if (params.isEmpty) "" else "?" + params.map { case (k, v) => s"$k=$v" }.mkString("&")
    HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path$query"))
      .GET()
      .header("Accept", "application/json")
      .build()
  }

  private def buildPost(path: String, body: String): HttpRequest =
    HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .build()

  private def send(request: HttpRequest, context: String): IO[AgentError, String] =
    ZIO.attemptBlocking(client.send(request, HttpResponse.BodyHandlers.ofString()))
      .mapError(t => AgentError.HttpFailed(s"$context: ${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)}", Some(t)))
      .flatMap { response =>
        val status = response.statusCode()
        val body   = response.body()
        if (status >= 400) ZIO.fail(AgentError.HttpBadStatus(s"$context returned $status", status, body))
        else ZIO.succeed(body)
      }
}
