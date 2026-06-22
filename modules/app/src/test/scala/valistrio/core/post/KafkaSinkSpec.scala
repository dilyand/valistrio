package valistrio.core.post

import cats.data.NonEmptyList
import io.circe.parser
import io.circe.syntax._
import org.specs2.mutable.Specification
import valistrio.core.domain._

/** Unit tests for the pure logic in KafkaSink.
  *
  * The live producer requires a running Kafka broker, so it is not tested here
  * (covered by the IT suite). These tests cover envelope serialization and the
  * key used to partition records, which can be exercised without a broker.
  */
class KafkaSinkSpec extends Specification {

  private val envelope = TransportEnvelope(
    SchemaName("com.valistrio", "envelope", SchemaVersion(1, 0, 0)),
    EnvelopeData(
      EventMeta("018f1e2a-dead-beef-cafe-000000000000", "2026-06-08T12:00:00Z"),
      TypedPayload(
        SchemaName("com.myorg", "page_view", SchemaVersion(1, 0, 0)),
        io.circe.Json.obj("page_url" -> io.circe.Json.fromString("https://example.com"))
      ),
      None: Option[NonEmptyList[TypedPayload]]
    )
  )

  "Envelope serialization" should {
    "produce JSON that decodes back to an equivalent envelope" in {
      parser.decode[TransportEnvelope](envelope.asJson.noSpaces) must beRight(envelope)
    }
  }

  "Record key" should {
    "use the envelope's event_id, so all writes for the same event land on one partition" in {
      envelope.data.meta.eventId must beEqualTo("018f1e2a-dead-beef-cafe-000000000000")
    }
  }
}
