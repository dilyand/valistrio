package valistrio.core.post

import cats.effect.IO
import fs2.kafka.KafkaProducer
import io.circe.Json
import io.circe.syntax._

/** A [[DlqSink]] backed by the shared `fs2-kafka` producer, writing the [[FailedEvent]] JSON to
  * `topic`. Keyed by the failed event's `event_id` when the original is available and carries one,
  * so a given event's failures colocate; otherwise the record is written with a `null` key.
  */
final class KafkaDlqSink(producer: KafkaProducer[IO, String, String], topic: String) extends DlqSink {
  def write(failed: FailedEvent): IO[Unit] =
    KafkaSink.produce(producer, topic, KafkaDlqSink.keyOf(failed), failed.asJson.noSpaces)
}

object KafkaDlqSink {

  /** Best-effort `event_id` for the record key: `null` when the original was truncated, is not a
    * JSON object, or carries no id (e.g. a malformed body). Kafka permits a `null` key.
    */
  private def keyOf(failed: FailedEvent): String =
    failed.original
      .flatMap(eventId)
      .orNull

  private def eventId(original: Json): Option[String] =
    original.hcursor.downField("data").downField("meta").get[String]("event_id").toOption
}
