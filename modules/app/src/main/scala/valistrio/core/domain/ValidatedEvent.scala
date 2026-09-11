package valistrio.core.domain

import io.circe.Json

/** An event that has passed full validation — the event structure plus every payload
  * (`body` and each context) against its own schema.
  *
  * As a [[Writable]] it is what a `Sink[ValidatedEvent]` (the events sink) accepts, so writing an
  * unvalidated event there is a type error. It carries the faithful original `json` (written to the
  * sink verbatim) and the `eventId` used as the record key.
  */
final case class ValidatedEvent private (json: Json, eventId: String) extends Writable {
  def key: Option[String] = Some(eventId)
}

object ValidatedEvent {

  /** Build from a fully-validated [[Event]] by reading the event id from its meta.
    * Callers construct this only once every payload has validated; a missing id here
    * would mean the event passed a schema that requires it — a bug.
    */
  def of(event: Event): Either[String, ValidatedEvent] =
    event.data.meta.hcursor
      .get[String]("event_id")
      .left.map(_.getMessage)
      .map(id => ValidatedEvent(event.json, id))
}
