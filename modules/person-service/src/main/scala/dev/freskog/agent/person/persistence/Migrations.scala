package dev.freskog.agent.person.persistence

import zio._

object Migrations {

  val migrations: List[String] = List(
    // V1: Core tables (scope-free; state is keyed by person + entity graph)
    """CREATE TABLE IF NOT EXISTS persons (
      |  id TEXT PRIMARY KEY,
      |  display_name TEXT NOT NULL,
      |  timezone TEXT NOT NULL,
      |  default_locale TEXT,
      |  active INTEGER NOT NULL DEFAULT 1
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS commitments (
      |  id TEXT PRIMARY KEY,
      |  owner_person_id TEXT NOT NULL,
      |  status TEXT NOT NULL DEFAULT 'proposed',
      |  text TEXT NOT NULL,
      |  source TEXT NOT NULL,
      |  evidence TEXT NOT NULL,
      |  due_at TEXT,
      |  created_at TEXT NOT NULL,
      |  updated_at TEXT NOT NULL,
      |  FOREIGN KEY (owner_person_id) REFERENCES persons(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS memory_items (
      |  id TEXT PRIMARY KEY,
      |  person_id TEXT,
      |  status TEXT NOT NULL DEFAULT 'proposed',
      |  kind TEXT NOT NULL,
      |  text TEXT NOT NULL,
      |  source TEXT NOT NULL,
      |  confidence REAL,
      |  created_at TEXT NOT NULL,
      |  updated_at TEXT NOT NULL,
      |  superseded_by_id TEXT,
      |  valid_from TEXT,
      |  valid_until TEXT,
      |  origin_event_id TEXT,
      |  FOREIGN KEY (person_id) REFERENCES persons(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS approvals (
      |  id TEXT PRIMARY KEY,
      |  requested_by TEXT NOT NULL,
      |  required_person_id TEXT,
      |  action_type TEXT NOT NULL,
      |  payload_json TEXT NOT NULL,
      |  status TEXT NOT NULL DEFAULT 'requested',
      |  created_at TEXT NOT NULL,
      |  decided_at TEXT,
      |  FOREIGN KEY (required_person_id) REFERENCES persons(id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS audit_events (
      |  id TEXT PRIMARY KEY,
      |  actor TEXT NOT NULL,
      |  action TEXT NOT NULL,
      |  category TEXT NOT NULL DEFAULT 'state',
      |  target_type TEXT NOT NULL,
      |  target_id TEXT,
      |  text TEXT,
      |  payload_json TEXT NOT NULL,
      |  created_at TEXT NOT NULL
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS goals (
      |  id TEXT PRIMARY KEY,
      |  owner_person_id TEXT NOT NULL,
      |  title TEXT NOT NULL,
      |  outcome TEXT NOT NULL,
      |  evidence_rule TEXT NOT NULL,
      |  constraints_json TEXT,
      |  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','blocked','done','cancelled')),
      |  blocked_reason TEXT,
      |  source TEXT,
      |  created_at TEXT NOT NULL,
      |  updated_at TEXT NOT NULL,
      |  FOREIGN KEY (owner_person_id) REFERENCES persons(id)
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

    "CREATE INDEX IF NOT EXISTS idx_goals_owner_status ON goals(owner_person_id, status)",

    "CREATE INDEX IF NOT EXISTS idx_goal_evidence_goal ON goal_evidence(goal_id)",

    "CREATE INDEX IF NOT EXISTS idx_memory_items_subject ON memory_items(person_id, kind, status)",
    "CREATE INDEX IF NOT EXISTS idx_memory_items_supersed ON memory_items(superseded_by_id)",
    "CREATE INDEX IF NOT EXISTS idx_memory_items_source ON memory_items(source)",

    "CREATE INDEX IF NOT EXISTS idx_audit_events_cat ON audit_events(category, created_at)",

    // Household graph: entities (nodes) + relationships (typed edges)
    """CREATE TABLE IF NOT EXISTS entities (
      |  id TEXT PRIMARY KEY,
      |  kind TEXT NOT NULL,
      |  name TEXT NOT NULL,
      |  attributes_json TEXT,
      |  status TEXT NOT NULL DEFAULT 'proposed',
      |  source TEXT NOT NULL,
      |  confidence REAL,
      |  superseded_by_id TEXT,
      |  created_at TEXT NOT NULL,
      |  updated_at TEXT NOT NULL
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS relationships (
      |  id TEXT PRIMARY KEY,
      |  from_id TEXT NOT NULL,
      |  from_kind TEXT NOT NULL,
      |  rel_type TEXT NOT NULL,
      |  to_id TEXT NOT NULL,
      |  to_kind TEXT NOT NULL,
      |  status TEXT NOT NULL DEFAULT 'proposed',
      |  source TEXT NOT NULL,
      |  confidence REAL,
      |  note TEXT,
      |  superseded_by_id TEXT,
      |  valid_from TEXT,
      |  valid_until TEXT,
      |  created_at TEXT NOT NULL,
      |  updated_at TEXT NOT NULL
      |)""".stripMargin,

    "CREATE INDEX IF NOT EXISTS idx_entities_kind_status ON entities(kind, status)",
    "CREATE INDEX IF NOT EXISTS idx_entities_name ON entities(name)",
    "CREATE INDEX IF NOT EXISTS idx_relationships_from ON relationships(from_id, rel_type, status)",
    "CREATE INDEX IF NOT EXISTS idx_relationships_to ON relationships(to_id, rel_type, status)",

    // FTS5 indexes with external-content + sync triggers
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

    "CREATE INDEX IF NOT EXISTS idx_messages_channel_time ON messages(channel_id, created_at DESC)",

    // V5: Gmail credentials + inbox messages
    """CREATE TABLE IF NOT EXISTS credentials (
      |  id TEXT PRIMARY KEY,
      |  provider TEXT NOT NULL,
      |  account_email TEXT NOT NULL,
      |  owner_person_id TEXT NOT NULL,
      |  access_token TEXT NOT NULL,
      |  refresh_token TEXT NOT NULL,
      |  expires_at TEXT NOT NULL,
      |  scopes TEXT NOT NULL,
      |  updated_at TEXT NOT NULL,
      |  FOREIGN KEY (owner_person_id) REFERENCES persons(id),
      |  UNIQUE (provider, owner_person_id)
      |)""".stripMargin,

    """CREATE TABLE IF NOT EXISTS inbox_messages (
      |  id TEXT PRIMARY KEY,
      |  provider TEXT NOT NULL,
      |  external_id TEXT NOT NULL,
      |  thread_id TEXT,
      |  from_addr TEXT NOT NULL,
      |  subject TEXT NOT NULL,
      |  body_text TEXT NOT NULL,
      |  received_at TEXT NOT NULL,
      |  owner_person_id TEXT NOT NULL,
      |  triage_status TEXT NOT NULL DEFAULT 'pending' CHECK (triage_status IN ('pending','triaged','skipped')),
      |  triaged_at TEXT,
      |  source_event_id TEXT,
      |  attachments_json TEXT NOT NULL DEFAULT '[]',
      |  FOREIGN KEY (owner_person_id) REFERENCES persons(id),
      |  UNIQUE (provider, external_id, owner_person_id)
      |)""".stripMargin,

    "CREATE INDEX IF NOT EXISTS idx_inbox_owner_status ON inbox_messages(owner_person_id, triage_status, received_at DESC)",

    // Source lookup for idempotent propose-by-source (dedup as a tool guarantee)
    "CREATE INDEX IF NOT EXISTS idx_commitments_source ON commitments(owner_person_id, source)",
    "CREATE INDEX IF NOT EXISTS idx_goals_source ON goals(owner_person_id, source)"
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
