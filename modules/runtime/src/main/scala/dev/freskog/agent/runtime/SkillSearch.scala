package dev.freskog.agent.runtime

import zio._

import java.sql.DriverManager

final case class SkillHit(name: String, description: String, path: String, score: Double)

object SkillSearch {

  // Force the sqlite-jdbc driver to register on the DriverManager. Modern
  // sqlite-jdbc auto-registers via the service loader, but in parallel
  // test runs the class may not have been touched yet on this classloader.
  Class.forName("org.sqlite.JDBC")

  def search(skills: List[Skill], query: String, limit: Int): IO[SkillError, List[SkillHit]] = {
    val trimmed = query.trim
    val sanitized = sanitizeQuery(trimmed)
    if (skills.isEmpty || sanitized.isEmpty) ZIO.succeed(Nil)
    else
      ZIO.attemptBlocking {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
          val create = conn.createStatement()
          create.execute(
            "CREATE VIRTUAL TABLE skills USING fts5(name, description, body, tokenize='unicode61 remove_diacritics 1')"
          )
          create.close()

          val insert = conn.prepareStatement("INSERT INTO skills (name, description, body) VALUES (?, ?, ?)")
          skills.foreach { s =>
            insert.setString(1, s.name)
            insert.setString(2, s.description)
            insert.setString(3, s.body)
            insert.executeUpdate()
          }
          insert.close()

          val nameIndex = skills.zipWithIndex.map { case (s, i) => s.name -> i }.toMap

          val stmt = conn.prepareStatement(
            "SELECT name, description, bm25(skills) AS score FROM skills WHERE skills MATCH ? ORDER BY bm25(skills) LIMIT ?"
          )
          stmt.setString(1, sanitized)
          stmt.setInt(2, limit)
          val rs = stmt.executeQuery()

          val results = scala.collection.mutable.ListBuffer.empty[SkillHit]
          while (rs.next()) {
            val name = rs.getString("name")
            val desc = rs.getString("description")
            val raw  = rs.getDouble("score")
            // bm25() returns lower-is-better; expose a higher-is-better score for the agent.
            val score = -raw
            val path  = nameIndex.get(name).map(skills(_).path).getOrElse("")
            results += SkillHit(name, desc, path, score)
          }
          rs.close()
          stmt.close()
          results.toList
        } finally conn.close()
      }.mapError(e => SkillError.IoError(s"FTS5 search failed: ${e.getMessage}"))
  }

  // Natural-language query: strip FTS5 metacharacters, drop very short
  // tokens, append prefix-match `*` to each remaining token, and OR them
  // together so partial overlap still ranks via BM25.
  private[runtime] def sanitizeQuery(raw: String): String = {
    val cleaned = raw.map {
      case c if c.isLetterOrDigit => c
      case ' ' | '\t' | '\n'      => ' '
      case _                      => ' '
    }
    val tokens = cleaned.split("\\s+").iterator.filter(_.length >= 2).toList
    if (tokens.isEmpty) ""
    else tokens.map(_ + "*").mkString(" OR ")
  }
}
