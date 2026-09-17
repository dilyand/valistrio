package valistrio.core.adapters

import io.circe.Json
import io.circe.syntax._
import valistrio.core.domain.SchemaRef

/** Assembles the valistrio event document (`io.github.dilyand.valistrio/event/1.0.0`) from an event
  * received through an inbound third-party adapter. Each vendor adapter translates its own wire
  * format into the parts below; this is the single place the document is assembled, shared across
  * adapters.
  *
  * Pure and total. The event schema, not this builder, remains the structural authority: a document
  * assembled here is validated downstream by the pipeline exactly like a direct `/post` body, so a
  * malformed `event_id` or an unregistered payload schema is caught there, not here.
  */
object EventFromThirdParty {

  val EventSchema: String = "io.github.dilyand.valistrio/event/1.0.0"

  /** `contexts` is omitted entirely when empty: the event schema permits no `contexts` key but
    * requires at least one entry when the key is present.
    */
  def build(
    eventId: String,
    producedAt: String,
    bodySchema: SchemaRef,
    bodyData: Json,
    contexts: List[(SchemaRef, Json)]
  ): Json = {
    val meta = Json.obj("event_id" -> eventId.asJson, "produced_at" -> producedAt.asJson)
    val data = Json.obj("meta" -> meta, "body" -> typed(bodySchema, bodyData))
    val withContexts =
      if (contexts.isEmpty) data
      else data.deepMerge(Json.obj("contexts" -> Json.fromValues(contexts.map((typed _).tupled))))
    Json.obj("schema" -> EventSchema.asJson, "data" -> withContexts)
  }

  private def typed(schema: SchemaRef, data: Json): Json =
    Json.obj("schema" -> schema.asJson, "data" -> data)
}
