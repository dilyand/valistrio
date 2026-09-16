package valistrio.core.domain

import cats.data.NonEmptyList
import io.circe.{Encoder, Json}
import io.circe.syntax._

import java.nio.charset.StandardCharsets
import java.time.Instant

/** A keyed JSON document a sink writes to a topic: the record `key` (the event id when known) and
  * the JSON `json` body. Its two forms — the validated event and the packaged failure — live in the
  * companion, so one sink can carry either; the topic it writes to is fixed at construction.
  */
sealed trait Writable {
  def key: Option[String]
  def json: Json
}

object Writable {

  /** An event that has passed full validation — the event structure plus the `body` and each
    * context against its own schema.
    *
    * Its constructor is `private[core]` and [[valistrio.core.pipeline.Validation]] is its only
    * producer, so the events sink (a `Sink[ValidatedEvent]`) accepts only events the pipeline has
    * validated — nothing outside `core` can forge one. It carries the faithful original `json`
    * (written to the sink verbatim) and the `eventId` used as the record key.
    */
  final case class ValidatedEvent private[core] (json: Json, eventId: String) extends Writable {
    def key: Option[String] = Some(eventId)
  }

  /** A failed event packaged for the DLQ: the faithful original JSON of the event Valistrio
    * accepted, the collected validation errors, and the time it failed.
    *
    * If the fully-encoded record (original + errors + metadata) exceeds `maxBytes`, the `original`
    * is dropped (`original` → `None`, `truncated` → `true`) so the record stays within the inbound
    * size limit — an oversized original is flagged, never silently lost. The errors/metadata are
    * assumed to fit on their own; a wrapper that exceeds `maxBytes` even without the original is
    * written as-is (the errors list is bounded by the number of schema violations).
    *
    * As a [[Writable]] its `json` is the wrapper below and its `key` is the original's `event_id`
    * when available (a truncated or malformed original has none, so the record is written unkeyed).
    */
  final case class FailedEvent private (
    original: Option[Json],
    truncated: Boolean,
    errors: NonEmptyList[ResponseError],
    failedAt: Instant
  ) extends Writable {
    def key: Option[String] = original.flatMap(FailedEvent.eventId)
    def json: Json          = this.asJson
  }

  object FailedEvent {

    def of(original: Json, errors: NonEmptyList[ResponseError], failedAt: Instant, maxBytes: Long): FailedEvent = {
      val full   = FailedEvent(Some(original), truncated = false, errors, failedAt)
      val tooBig = full.json.noSpaces.getBytes(StandardCharsets.UTF_8).length > maxBytes
      if (tooBig) FailedEvent(None, truncated = true, errors, failedAt) else full
    }

    private def eventId(original: Json): Option[String] =
      original.hcursor.downField("data").downField("meta").get[String]("event_id").toOption

    implicit val encoder: Encoder[FailedEvent] = Encoder.instance { fe =>
      val base = Json.obj(
        "original"  -> fe.original.getOrElse(Json.Null),
        "errors"    -> fe.errors.toList.asJson,
        "failed_at" -> fe.failedAt.toString.asJson
      )
      if (fe.truncated) base.deepMerge(Json.obj("original_truncated" -> Json.True)) else base
    }
  }
}
