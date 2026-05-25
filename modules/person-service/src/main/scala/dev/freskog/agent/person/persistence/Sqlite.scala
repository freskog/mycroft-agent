package dev.freskog.agent.person.persistence

import zio._

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}

trait Sqlite {
  def execute(sql: String, params: Any*): Task[Unit]
  def query[A](sql: String, params: Any*)(extract: ResultSet => A): Task[List[A]]
  def queryOne[A](sql: String, params: Any*)(extract: ResultSet => A): Task[Option[A]]
}

object Sqlite {

  def live(dbPath: String): ZLayer[Any, Throwable, Sqlite] =
    ZLayer.scoped {
      for {
        conn <- ZIO.acquireRelease(
          ZIO.attemptBlocking {
            val c = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
            c.setAutoCommit(true)
            if (!dbPath.contains(":memory:")) {
              val s1 = c.createStatement()
              s1.execute("PRAGMA journal_mode=WAL")
              s1.close()
            }
            val s2 = c.createStatement()
            s2.execute("PRAGMA foreign_keys=ON")
            s2.close()
            c
          }
        )(c => ZIO.attemptBlocking(c.close()).ignore)
        sem <- Semaphore.make(1)
      } yield new SqliteLive(conn, sem)
    }

  private class SqliteLive(val conn: Connection, sem: Semaphore) extends Sqlite {

    def execute(sql: String, params: Any*): Task[Unit] =
      sem.withPermit {
        ZIO.attemptBlocking {
          val stmt = conn.prepareStatement(sql)
          bindParams(stmt, params)
          stmt.executeUpdate()
          stmt.close()
        }
      }

    def query[A](sql: String, params: Any*)(extract: ResultSet => A): Task[List[A]] =
      sem.withPermit {
        ZIO.attemptBlocking {
          val stmt = conn.prepareStatement(sql)
          bindParams(stmt, params)
          val rs = stmt.executeQuery()
          val results = Iterator.continually(rs.next()).takeWhile(identity).map(_ => extract(rs)).toList
          rs.close()
          stmt.close()
          results
        }
      }

    def queryOne[A](sql: String, params: Any*)(extract: ResultSet => A): Task[Option[A]] =
      query(sql, params: _*)(extract).map(_.headOption)

    private def bindParams(stmt: PreparedStatement, params: Seq[Any]): Unit =
      params.zipWithIndex.foreach { case (param, idx) =>
        param match {
          case s: String       => stmt.setString(idx + 1, s)
          case i: Int          => stmt.setInt(idx + 1, i)
          case l: Long         => stmt.setLong(idx + 1, l)
          case d: Double       => stmt.setDouble(idx + 1, d)
          case b: Boolean      => stmt.setBoolean(idx + 1, b)
          case None            => stmt.setNull(idx + 1, java.sql.Types.NULL)
          case Some(v)         => bindParams(stmt, Seq(v)); ()
          case null            => stmt.setNull(idx + 1, java.sql.Types.NULL)
          case other           => stmt.setString(idx + 1, other.toString)
        }
      }
  }
}
