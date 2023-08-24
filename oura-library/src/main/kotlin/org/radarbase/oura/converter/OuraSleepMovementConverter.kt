package org.radarbase.oura.converter

import com.fasterxml.jackson.databind.JsonNode
import org.radarcns.connector.oura.OuraSleepMovement
import org.radarcns.connector.oura.OuraSleepMovementType
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.io.IOException
import org.radarbase.oura.user.User

class OuraSleepMovementConverter(
    private val topic: String = "connect_oura_sleep_movement",
) : OuraDataConverter {

    final val SLEEP_MOVEMENT_INTERVAL = 30 // in seconds

    @Throws(IOException::class)
    override fun processRecords(
        root: JsonNode,
        user: User
    ): Sequence<Result<TopicData>> {
        val array = root.get("data")
            ?: return emptySequence()
        return array.asSequence()
        .flatMap { 
            it.processSamples(user)
        }
    }

    private fun JsonNode.processSamples(
        user: User
    ): Sequence<Result<TopicData>> {
        val startTime = OffsetDateTime.parse(this["bedtime_start"].textValue())
        val startTimeEpoch = startTime.toInstant().toEpochMilli() / 1000.0
        val timeReceivedEpoch = System.currentTimeMillis() / 1000.0
        val id = this.get("id").textValue()
        val items = this.get("movement_30_sec").textValue().toCharArray()
        if (items == null) return emptySequence()
        else {
            return items.asSequence()
                .mapIndexedCatching { index, value ->
                    TopicData(
                        key = user.observationKey,
                        topic = topic,
                        value = toSleepPhase(
                            startTimeEpoch,
                            timeReceivedEpoch,
                            id,
                            index,
                            SLEEP_MOVEMENT_INTERVAL,
                            value.toString()),
                    )
                }
        }
    }

    private fun toSleepPhase(
        startTimeEpoch: Double,
        timeReceivedEpoch: Double,
        idString: String,
        index: Int,
        interval: Int,
        value: String
    ): OuraSleepMovement {
        val offset = interval * index
        return OuraSleepMovement.newBuilder().apply {
            id = idString
            time = startTimeEpoch + offset
            timeReceived = timeReceivedEpoch
            movement = value.classify()
        }.build()
    }

    private fun String.classify() : OuraSleepMovementType {
        return when (this) {
            "1" -> OuraSleepMovementType.NO_MOTION
            "2" -> OuraSleepMovementType.RESTLESS
            "3" -> OuraSleepMovementType.TOSSING_AND_TURNING
            "4" -> OuraSleepMovementType.ACTIVE
            else -> OuraSleepMovementType.UNKNOWN
        }
    }

    companion object {
        val logger = LoggerFactory.getLogger(OuraSleepMovementConverter::class.java)
    }
}
