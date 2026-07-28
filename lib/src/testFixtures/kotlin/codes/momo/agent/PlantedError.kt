package codes.momo.agent

/**
 * The throwable the cases about a throwable escaping a run plant, wherever
 * they plant it — a listener, or a [FakeLlmReply.Thrown]. A named subclass
 * because an `AssertionError` is indistinguishable from a failed assertion of
 * the case itself.
 */
public class PlantedError(message: String) : Error(message)
