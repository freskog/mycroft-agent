package dev.freskog.agent.person.cli

import zio._
import zio.json._

import java.net.URI
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}

object HttpClient {

  private val baseUrl: String =
    java.lang.System.getenv().getOrDefault("PERSON_SERVICE_URL", "http://127.0.0.1:8080")

  private val client: JHttpClient = JHttpClient.newHttpClient()

  def get(path: String, params: Map[String, String] = Map.empty): Task[String] =
    ZIO.attemptBlocking {
      val query = if (params.isEmpty) "" else "?" + params.map { case (k, v) => s"$k=$v" }.mkString("&")
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path$query"))
        .GET()
        .header("Accept", "application/json")
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() >= 400)
        throw new RuntimeException(s"HTTP ${response.statusCode()}: ${response.body()}")
      response.body()
    }

  def post(path: String, body: String): Task[String] =
    ZIO.attemptBlocking {
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() >= 400)
        throw new RuntimeException(s"HTTP ${response.statusCode()}: ${response.body()}")
      response.body()
    }
}
