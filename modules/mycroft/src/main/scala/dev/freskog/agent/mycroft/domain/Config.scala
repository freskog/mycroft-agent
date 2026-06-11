package dev.freskog.agent.mycroft.domain

/** Environment-driven configuration, read once at startup. */
final case class MycroftConfig(
  port: Int,
  host: String,
  personServiceUrl: String,
  lmStudioUrl: String,
  defaultModel: String,
  maxToolIterations: Int,
  maxSkillDepth: Int,
  maxTurnSeconds: Int,
  maxOutputTokens: Int,
  defaultReasoning: String,
  reasonMaxTokens: Int,
  directMaxTokens: Int,
  contextWindowMsgs: Int,
  contextTokenBudget: Int,
  innerTokenBudget: Int,
  keepRecentTools: Int,
  temperature: Double,
  topP: Double,
  topK: Int,
  minP: Double,
  presencePenalty: Double,
  timezone: String
)

object MycroftConfig {

  private def env(key: String, default: String): String =
    sys.env.getOrElse(key, default)

  private def envInt(key: String, default: Int): Int =
    sys.env.get(key).flatMap(_.toIntOption).getOrElse(default)

  private def envDouble(key: String, default: Double): Double =
    sys.env.get(key).flatMap(_.toDoubleOption).getOrElse(default)

  def fromEnv: MycroftConfig =
    MycroftConfig(
      port               = envInt("MYCROFT_PORT", 8090),
      host               = env("MYCROFT_HOST", "0.0.0.0"),
      personServiceUrl   = env("PERSON_SERVICE_URL", "http://person-service:8080"),
      lmStudioUrl        = env("MYCROFT_LM_STUDIO_URL", "http://fredriks-mac-mini.gledswood.org:1234"),
      defaultModel       = env("MYCROFT_DEFAULT_MODEL", "qwen3.6-35b-a3b-ud-mlx"),
      maxToolIterations  = envInt("MYCROFT_MAX_TOOL_ITERATIONS", 120),
      maxSkillDepth      = envInt("MYCROFT_MAX_SKILL_DEPTH", 3),
      maxTurnSeconds     = envInt("MYCROFT_MAX_TURN_SECONDS", 600),
      maxOutputTokens    = envInt("MYCROFT_MAX_OUTPUT_TOKENS", 2048),
      // Reasoning mode for the top-level conversational turn. `reason` (default)
      // keeps thinking on with the existing 2048-token budget — i.e. no change to
      // current behaviour, just made explicit. `direct` turns thinking off with a
      // tighter budget: the opt-in speed lever (flip the env, or set per-skill via
      // `reasoning:` frontmatter). Budgets are env-tunable; 2048 stays the reason
      // default deliberately (a larger budget lets the model spin longer on the
      // rare no-answer loops the presence penalty guards against).
      defaultReasoning   = env("MYCROFT_DEFAULT_REASONING", "reason"),
      reasonMaxTokens    = envInt("MYCROFT_REASON_MAX_TOKENS", 2048),
      directMaxTokens    = envInt("MYCROFT_DIRECT_MAX_TOKENS", 1024),
      contextWindowMsgs  = envInt("MYCROFT_CONTEXT_WINDOW_MSGS", 20),
      contextTokenBudget = envInt("MYCROFT_CONTEXT_TOKEN_BUDGET", 8000),
      // The intra-turn working-set budget: the agentic loop appends a tool result
      // (up to 4 KB) per iteration, so without a cap a long turn overflows the
      // model. `fit` degrades the oldest tool outputs to their runlog pointer to
      // stay under this; the most recent `keepRecentTools` results stay verbatim.
      innerTokenBudget   = envInt("MYCROFT_INNER_TOKEN_BUDGET", 16000),
      keepRecentTools    = envInt("MYCROFT_KEEP_RECENT_TOOLS", 3),
      // Qwen3 thinking-mode sampling + a presence penalty to break reasoning
      // repetition loops (the model otherwise spins until max_tokens with no
      // answer). Tune via env if a different model is loaded.
      temperature        = envDouble("MYCROFT_TEMPERATURE", 0.6),
      topP               = envDouble("MYCROFT_TOP_P", 0.95),
      topK               = envInt("MYCROFT_TOP_K", 20),
      minP               = envDouble("MYCROFT_MIN_P", 0.0),
      presencePenalty    = envDouble("MYCROFT_PRESENCE_PENALTY", 1.0),
      // Timezone used to stamp the current date/time into the prompt so the model
      // can resolve relative dates and tell past from future. Override with
      // MYCROFT_TIMEZONE (an IANA zone id); compose sets Europe/Dublin.
      timezone           = env("MYCROFT_TIMEZONE", "UTC")
    )
}
