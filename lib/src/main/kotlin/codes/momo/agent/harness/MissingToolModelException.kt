package codes.momo.agent.harness

/** A run's settings name no model for [tools], each taking one somewhere in the harness tree. */
public class MissingToolModelException(tools: List<String>) :
    RuntimeException("No model is selected for ${tools.joinToString(", ")}; pick one for each before running.")
