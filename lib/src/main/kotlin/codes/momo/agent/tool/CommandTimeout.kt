package codes.momo.agent.tool

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** How a bash call names the longest its command may run: the one place that knows the format and the range. */
internal object CommandTimeout : KSerializer<Duration> {

    private const val LONGEST_HOURS: Int = 24

    const val FORMAT: String = "at most ${LONGEST_HOURS}h, as a number with a unit: \"30s\", \"10m\", \"6h\""

    private val ACCEPTED: ClosedRange<Duration> = 1.seconds..LONGEST_HOURS.hours

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CommandTimeout", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Duration =
        Duration.parseOrNull(decoder.decodeString())?.takeIf { it in ACCEPTED }
            ?: throw SerializationException("timeout must be at least ${ACCEPTED.start} and $FORMAT.")

    override fun serialize(encoder: Encoder, value: Duration): Unit = encoder.encodeString(value.toString())
}
