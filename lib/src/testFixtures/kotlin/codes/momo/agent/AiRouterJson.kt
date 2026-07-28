package codes.momo.agent

import kotlinx.serialization.json.Json

/**
 * The serialization settings the ai-router SDK configures its own `Json`
 * with, written out by hand because the SDK keeps that instance private.
 *
 * Nothing guards the copy. The SDK uses its own instance only inside the
 * Ktor client it builds itself, and every test tier hands it a client of its
 * own — so no test here encodes or decodes through the SDK's instance, and a
 * drift between the two shows up only in production, which does use the
 * SDK's default client.
 */
public val aiRouterSdkJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}
