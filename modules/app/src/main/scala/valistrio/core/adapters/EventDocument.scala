package valistrio.core.adapters

import io.circe.Json
import io.circe.syntax._

/** Assembles the valistrio event document (`io.github.dilyand.valistrio/event/1.0.0`) that inbound
  * adapters forward to the ingestion pipeline. Each vendor adapter translates its own wire format
  * into the vendor-neutral parts below; this is the single place the document is assembled, shared
  * across adapters.
  *
  * Pure and total. The event schema, not this builder, remains the structural authority: a document
  * assembled here is validated downstream by the pipeline exactly like a direct `/post` body, so a
  * malformed `event_id` or an unregistered payload schema is caught there, not here.
  */
object EventDocument {

  val EventSchema: String = "io.github.dilyand.valistrio/event/1.0.0"

  /** `contexts` is omitted entirely when empty: the event schema permits no `contexts` key but
    * requires at least one entry when the key is present.
    */
  def build(eventId: String, producedAt: String, body: TypedPayload, contexts: List[TypedPayload]): Json = {
    val meta = Json.obj("event_id" -> eventId.asJson, "produced_at" -> producedAt.asJson)
    val data =
      if (contexts.isEmpty) Json.obj("meta" -> meta, "body" -> body.asJson)
      else Json.obj("meta" -> meta, "body" -> body.asJson, "contexts" -> contexts.asJson)
    Json.obj("schema" -> EventSchema.asJson, "data" -> data)
  }
}
