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
      |)""".stripMargin,

    // V2: Goals + goal evidence
    """CREATE TABLE IF NOT EXISTS goals (
      |  id TEXT PRIMARY KEY,
      |  owner_person_id TEXT NOT NULL,
      |  scope_id TEXT NOT NULL,
      |  title TEXT NOT NULL,
      |  outcome TEXT NOT NULL,
      |  evidence_rule TEXT NOT NULL,
      |  constraints_json TEXT,
      |  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','blocked','done','cancelled')),
      |  blocked_reason TEXT,
      |  source TEXT,
      |  created_at TEXT NOT NULL,
      |  updated_at TEXT NOT NULL,
      |  FOREIGN KEY (owner_person_id) REFERENCES persons(id),
      |  FOREIGN KEY (scope_id) REFERENCES scopes(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS goal_evidence (
      |  id TEXT PRIMARY KEY,
      |  goal_id TEXT NOT NULL,
      |  kind TEXT NOT NULL,
      |  ref TEXT NOT NULL,
      |  note TEXT,
      |  recorded_at TEXT NOT NULL,
      |  FOREIGN KEY (goal_id) REFERENCES goals(id)
      |)""".stripMargin,

    "CREATE INDEX IF NOT EXISTS idx_goals_owner_scope_status ON goals(owner_person_id, scope_id, status)",

    "CREATE INDEX IF NOT EXISTS idx_goal_evidence_goal ON goal_evidence(goal_id)",

    // V3a: temporal + provenance on memory_items
    "ALTER TABLE memory_items ADD COLUMN superseded_by_id TEXT",
    "ALTER TABLE memory_items ADD COLUMN valid_from TEXT",
    "ALTER TABLE memory_items ADD COLUMN valid_until TEXT",
    "ALTER TABLE memory_items ADD COLUMN origin_event_id TEXT",
    "CREATE INDEX IF NOT EXISTS idx_memory_items_subject ON memory_items(person_id, scope_id, kind, status)",
    "CREATE INDEX IF NOT EXISTS idx_memory_items_supersed ON memory_items(superseded_by_id)",

    // V3b: broaden audit_events for episodic use
    "ALTER TABLE audit_events ADD COLUMN category TEXT NOT NULL DEFAULT 'state'",
    "ALTER TABLE audit_events ADD COLUMN text TEXT",
    "CREATE INDEX IF NOT EXISTS idx_audit_events_cat ON audit_events(category, scope_id, created_at)",

    // V3c: FTS5 indexes with external-content + sync triggers
    "CREATE VIRTUAL TABLE IF NOT EXISTS memory_items_fts USING fts5(text, content='memory_items', content_rowid='rowid', tokenize='unicode61 remove_diacritics 1')",
    "INSERT INTO memory_items_fts(rowid, text) SELECT rowid, text FROM memory_items",

    """CREATE TRIGGER IF NOT EXISTS memory_items_ai AFTER INSERT ON memory_items BEGIN
      |  INSERT INTO memory_items_fts(rowid, text) VALUES (new.rowid, new.text);
      |END""".stripMargin,

    """CREATE TRIGGER IF NOT EXISTS memory_items_ad AFTER DELETE ON memory_items BEGIN
      |  INSERT INTO memory_items_fts(memory_items_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
      |END""".stripMargin,

    """CREATE TRIGGER IF NOT EXISTS memory_items_au AFTER UPDATE ON memory_items BEGIN
      |  INSERT INTO memory_items_fts(memory_items_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
      |  INSERT INTO memory_items_fts(rowid, text) VALUES (new.rowid, new.text);
      |END""".stripMargin,

    "CREATE VIRTUAL TABLE IF NOT EXISTS audit_events_fts USING fts5(text, payload_json, content='audit_events', content_rowid='rowid', tokenize='unicode61 remove_diacritics 1')",
    "INSERT INTO audit_events_fts(rowid, text, payload_json) SELECT rowid, COALESCE(text, ''), payload_json FROM audit_events",

    """CREATE TRIGGER IF NOT EXISTS audit_events_ai AFTER INSERT ON audit_events BEGIN
      |  INSERT INTO audit_events_fts(rowid, text, payload_json) VALUES (new.rowid, COALESCE(new.text, ''), new.payload_json);
      |END""".stripMargin,

    """CREATE TRIGGER IF NOT EXISTS audit_events_ad AFTER DELETE ON audit_events BEGIN
      |  INSERT INTO audit_events_fts(audit_events_fts, rowid, text, payload_json) VALUES ('delete', old.rowid, COALESCE(old.text, ''), old.payload_json);
      |END""".stripMargin,

    """CREATE TRIGGER IF NOT EXISTS audit_events_au AFTER UPDATE ON audit_events BEGIN
      |  INSERT INTO audit_events_fts(audit_events_fts, rowid, text, payload_json) VALUES ('delete', old.rowid, COALESCE(old.text, ''), old.payload_json);
      |  INSERT INTO audit_events_fts(rowid, text, payload_json) VALUES (new.rowid, COALESCE(new.text, ''), new.payload_json);
      |END""".stripMargin,

    // V4: mycroft channels, audience, and message log
    """CREATE TABLE IF NOT EXISTS channels (
      |  id TEXT PRIMARY KEY,
      |  default_model TEXT,
      |  created_at TEXT NOT NULL
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS channel_members (
      |  channel_id TEXT NOT NULL,
      |  person_id TEXT NOT NULL,
      |  PRIMARY KEY (channel_id, person_id),
      |  FOREIGN KEY (channel_id) REFERENCES channels(id),
      |  FOREIGN KEY (person_id) REFERENCES persons(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS messages (
      |  id TEXT PRIMARY KEY,
      |  channel_id TEXT NOT NULL,
      |  role TEXT NOT NULL CHECK (role IN ('user','assistant','tool','system')),
      |  person_id_from TEXT,
      |  content TEXT NOT NULL,
      |  tool_calls_json TEXT,
      |  external_id TEXT,
      |  created_at TEXT NOT NULL,
      |  FOREIGN KEY (channel_id) REFERENCES channels(id)
      |)""".stripMargin,

    "CREATE INDEX IF NOT EXISTS idx_messages_channel_time ON messages(channel_id, created_at DESC)"
  )

  def migrate(db: Sqlite): IO[dev.freskog.agent.common.AgentError, Unit] =
    for {
      _              <- db.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
      current        <- db.queryOne("SELECT version FROM schema_version")(_.getInt("version"))
      currentVersion  = current.getOrElse(0)
      toApply         = migrations.drop(currentVersion)
      _              <- ZIO.foreach(toApply.zipWithIndex) { case (sql, idx) =>
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
