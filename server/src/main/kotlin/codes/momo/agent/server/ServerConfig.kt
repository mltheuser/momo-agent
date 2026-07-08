package codes.momo.agent.server

import java.nio.file.Path

/**
 * Server process configuration. Each setting resolves from its CLI argument
 * (`--name=value`), then its environment variable, then the default —
 * see the README's server section for the table.
 */
internal data class ServerConfig(
    val port: Int,
    val dataDir: Path,
    val aiRouterBaseUrl: String,
) {

    companion object {

        const val DEFAULT_PORT: Int = 8420

        const val DEFAULT_AI_ROUTER_BASE_URL: String = "http://localhost:8787"

        val DEFAULT_DATA_DIR: Path = Path.of(System.getProperty("user.home"), ".momo-agent")

        private val KNOWN_OPTIONS = setOf("port", "data-dir", "ai-router-base-url")

        private const val MAX_PORT = 65535

        /**
         * @throws IllegalArgumentException on an unknown or malformed
         *   argument, or a port outside 1..65535.
         */
        fun resolve(args: List<String>, env: Map<String, String>): ServerConfig {
            val options = parseOptions(args)

            fun setting(option: String, envVariable: String): String? =
                options[option] ?: env[envVariable]?.takeIf { it.isNotBlank() }

            val port = setting("port", "MOMO_AGENT_PORT")?.let {
                requireNotNull(it.toIntOrNull()?.takeIf { port -> port in 1..MAX_PORT }) { "Not a valid port: '$it'" }
            } ?: DEFAULT_PORT
            return ServerConfig(
                port = port,
                dataDir = setting("data-dir", "MOMO_AGENT_DATA_DIR")?.let(Path::of) ?: DEFAULT_DATA_DIR,
                aiRouterBaseUrl = setting("ai-router-base-url", "AI_ROUTER_BASE_URL")
                    ?: DEFAULT_AI_ROUTER_BASE_URL,
            )
        }

        private fun parseOptions(args: List<String>): Map<String, String> = args.associate { argument ->
            val match = requireNotNull(Regex("--([a-z-]+)=(.+)").matchEntire(argument)) {
                "Unrecognized argument: '$argument'. Expected --option=value with options: " +
                    KNOWN_OPTIONS.joinToString(", ") { "--$it" }
            }
            val (option, value) = match.destructured
            require(option in KNOWN_OPTIONS) {
                "Unknown option: '--$option'. Known options: ${KNOWN_OPTIONS.joinToString(", ") { "--$it" }}"
            }
            option to value
        }
    }
}
