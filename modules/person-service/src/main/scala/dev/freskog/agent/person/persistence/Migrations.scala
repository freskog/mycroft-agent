package dev.freskog.agent.person.persistence

import zio._

object Migrations {

  val migrations: List[String] = List(
    // V1: Core tables
    """CREATE TABLE IF NOT EXISTS persons (
      |  id TEXT PRIMARY KEY,
      |  display_name TEXT NOT NULL,
      |  timezone TEXT NOT NULL,
      |  default_locale TEXT,
      |  active INTEGER NOT NULL DEFAULT 1
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS scopes (
      |  id TEXT PRIMARY KEY,
      |  name TEXT NOT NULL,
      |  owner_person_id TEXT,
      |  kind TEXT NOT NULL,
      |  FOREIGN KEY (owner_person_id) REFERENCES persons(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS person_scope_roles (
      |  person_id TEXT NOT NULL,
      |  scope_id TEXT NOT NULL,
      |  role TEXT NOT NULL,
      |  PRIMARY KEY (person_id, scope_id, role),
      |  FOREIGN KEY (person_id) REFERENCES persons(id),
      |  FOREIGN KEY (scope_id) REFERENCES scopes(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS commitments (
      |  id TEXT PRIMARY KEY,
      |  owner_person_id TEXT NOT NULL,
      |  scope_id TEXT NOT NULL,
      |  status TEXT NOT NULL DEFAULT 'proposed',
      |  text TEXT NOT NULL,
      |  source TEXT NOT NULL,
      |  evidence TEXT NOT NULL,
      |  due_at TEXT,
      |  created_at TEXT NOT NULL,
      |  updated_at TEXT NOT NULL,
      |  FOREIGN KEY (owner_person_id) REFERENCES persons(id),
      |  FOREIGN KEY (scope_id) REFERENCES scopes(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS memory_items (
      |  id TEXT PRIMARY KEY,
      |  person_id TEXT,
      |  scope_id TEXT,
      |  status TEXT NOT NULL DEFAULT 'proposed',
      |  kind TEXT NOT NULL,
      |  text TEXT NOT NULL,
      |  source TEXT NOT NULL,
      |  confidence REAL,
      |  created_at TEXT NOT NULL,
      |  updated_at TEXT NOT NULL,
      |  FOREIGN KEY (person_id) REFERENCES persons(id),
      |  FOREIGN KEY (scope_id) REFERENCES scopes(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS approvals (
      |  id TEXT PRIMARY KEY,
      |  requested_by TEXT NOT NULL,
      |  required_person_id TEXT,
      |  scope_id TEXT,
      |  action_type TEXT NOT NULL,
      |  payload_json TEXT NOT NULL,
      |  status TEXT NOT NULL DEFAULT 'requested',
      |  created_at TEXT NOT NULL,
      |  decided_at TEXT,
      |  FOREIGN KEY (required_person_id) REFERENCES persons(id),
      |  FOREIGN KEY (scope_id) REFERENCES scopes(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS audit_events (
      |  id TEXT PRIMARY KEY,
      |  actor TEXT NOT NULL,
      |  action TEXT NOT NULL,
      |  target_type TEXT NOT NULL,
      |  target_id TEXT,
      |  scope_id TEXT,
      |  payload_json TEXT NOT NULL,
      |  created_at TEXT NOT NULL
      |)""".stripMargin
  )

  def migrate(db: Sqlite): Task[Unit] =
    for {
      _ <- db.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
      current <- db.queryOne("SELECT version FROM schema_version")(_.getInt("version"))
      currentVersion = current.getOrElse(0)
      toApply = migrations.drop(currentVersion)
      _ <- ZIO.foreach(toApply.zipWithIndex) { case (sql, idx) =>
        val version = currentVersion + idx + 1
        db.execute(sql) *> {
          if (currentVersion == 0 && idx == 0)
            db.execute("INSERT INTO schema_version (version) VALUES (?)", version)
          else
            db.execute("UPDATE schema_version SET version = ?", version)
        }
      }
    } yield ()
}
