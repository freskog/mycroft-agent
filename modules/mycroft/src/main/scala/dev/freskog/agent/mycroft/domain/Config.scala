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
  contextWindowMsgs: Int,
  contextTokenBudget: Int,
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
      contextWindowMsgs  = envInt("MYCROFT_CONTEXT_WINDOW_MSGS", 20),
      contextTokenBudget = envInt("MYCROFT_CONTEXT_TOKEN_BUDGET", 8000),
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
