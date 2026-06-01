package dev.freskog.agent.person.persistence

import dev.freskog.agent.common.{AgentError, Time}

import zio._

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import java.time.Instant

trait Sqlite {
  def execute(sql: String, params: Any*): IO[AgentError, Unit]
  def query[A](sql: String, params: Any*)(extract: ResultSet => A): IO[AgentError, List[A]]
  def queryOne[A](sql: String, params: Any*)(extract: ResultSet => A): IO[AgentError, Option[A]]
}

object Sqlite {

  // Force the sqlite-jdbc driver to register on the DriverManager. Auto-
  // registration via the service loader is racy when several modules each
  // open SQLite connections from parallel test JVMs.
  Class.forName("org.sqlite.JDBC")

  def live(dbPath: String): ZLayer[Any, AgentError, Sqlite] =
    ZLayer.scoped {
      for {
        conn <- ZIO.acquireRelease(openConnection(dbPath))(c =>
          ZIO.attemptBlocking(c.close()).ignore
        )
        sem <- Semaphore.make(1)
      } yield new SqliteLive(conn, sem)
    }

  private def openConnection(dbPath: String): IO[AgentError, Connection] =
    ZIO.attemptBlocking {
      val c = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
      c.setAutoCommit(true)
      if (!dbPath.contains(":memory:")) runPragma(c, "PRAGMA journal_mode=WAL")
      runPragma(c, "PRAGMA foreign_keys=ON")
      c
    }.refineOrDie { case t: Throwable => AgentError.fromThrowable(s"opening $dbPath")(t) }

  private def runPragma(conn: Connection, sql: String): Unit = {
    val st = conn.createStatement()
    try st.execute(sql)
    finally st.close()
  }

  private class SqliteLive(conn: Connection, sem: Semaphore) extends Sqlite {

    def execute(sql: String, params: Any*): IO[AgentError, Unit] =
      sem.withPermit(blocking(sql) {
        withStatement(sql) { stmt =>
          bindParams(stmt, params)
          val _ = stmt.executeUpdate()
          ()
        }
      })

    def query[A](sql: String, params: Any*)(extract: ResultSet => A): IO[AgentError, List[A]] =
      sem.withPermit(blocking(sql) {
        withStatement(sql) { stmt =>
          bindParams(stmt, params)
          val rs = stmt.executeQuery()
          try drain(rs, extract)
          finally rs.close()
        }
      })

    def queryOne[A](sql: String, params: Any*)(extract: ResultSet => A): IO[AgentError, Option[A]] =
      query(sql, params: _*)(extract).map(_.headOption)

    private def blocking[A](context: String)(body: => A): IO[AgentError, A] =
      ZIO.attemptBlocking(body).refineOrDie {
        case t: Throwable => AgentError.fromThrowable(s"sqlite: $context")(t)
      }

    private def withStatement[A](sql: String)(use: PreparedStatement => A): A = {
      val stmt = conn.prepareStatement(sql)
      try use(stmt)
      finally stmt.close()
    }

    private def drain[A](rs: ResultSet, extract: ResultSet => A): List[A] = {
      val buf = List.newBuilder[A]
      while (rs.next()) buf += extract(rs)
      buf.result()
    }

    /** Bind a single param at 1-based JDBC index. Recurses into `Some` and
     *  unwraps the project's `AnyVal` newtype IDs. Throws a `Bug` for any
     *  type we don't expect — silent `.toString` fallthrough could corrupt
     *  writes if a method-signature change ever slipped a wrong type past
     *  the compiler. */
    private def bindOne(stmt: PreparedStatement, idx: Int, param: Any): Unit = param match {
      case null                  => stmt.setNull(idx, java.sql.Types.NULL)
      case None                  => stmt.setNull(idx, java.sql.Types.NULL)
      case Some(v)               => bindOne(stmt, idx, v)
      case s: String             => stmt.setString(idx, s)
      case i: Int                => stmt.setInt(idx, i)
      case l: Long               => stmt.setLong(idx, l)
      case d: Double             => stmt.setDouble(idx, d)
      case b: Boolean            => stmt.setBoolean(idx, b)
      case t: Instant            => stmt.setString(idx, Time.format(t))
      case p: Product            => bindOne(stmt, idx, unwrapNewtype(p))
      case other                 =>
        throw new IllegalArgumentException(
          s"sqlite bindParams: unbindable parameter of type ${other.getClass.getName}"
        )
    }

    private def unwrapNewtype(p: Product): Any =
      if (p.productArity == 1) p.productElement(0)
      else throw new IllegalArgumentException(
        s"sqlite bindParams: non-newtype Product not supported: ${p.getClass.getName}"
      )

    private def bindParams(stmt: PreparedStatement, params: Seq[Any]): Unit =
      params.iterator.zipWithIndex.foreach { case (p, i) => bindOne(stmt, i + 1, p) }
  }
}
