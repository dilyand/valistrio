package valistrio.core.post

import cats.effect.{IO, Resource}
import fs2.kafka._
import io.circe.syntax._
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig}
import org.apache.kafka.common.errors.{
  RecordTooLargeException,
  SerializationException,
  TimeoutException => KafkaTimeoutException,
  UnknownTopicOrPartitionException
}
import valistrio.core.Config.KafkaConfig
import valistrio.core.ValistrioError.SinkError
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.domain.Event

import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

object KafkaSink {

  private val ConnectivityCheckTimeout = 5.seconds

  /** Creates a [[Sink]] backed by an `fs2-kafka` producer, writing to `config.topic`.
    *
    * On acquisition, probes broker connectivity so the app fails fast at startup if
    * Kafka is unreachable rather than only on the first `/post`. The probe checks
    * cluster reachability only (not that `config.topic` exists) — topics are commonly
    * provisioned out-of-band, and brokers often disable auto-topic-creation.
    */
  def resource(config: KafkaConfig): Resource[IO, Sink] =
    Resource.eval(checkConnectivity(config)).flatMap { _ =>
      producerResource(config).map { producer =>
        new Sink {
          def write(event: Event): IO[Either[SinkError, Unit]] = {
            val record = ProducerRecord(config.topic, event.data.meta.eventId, event.asJson.noSpaces)
            producer.produceOne_(record).flatten.attempt.map {
              case Right(_)                                  => Right(())
              case Left(_: KafkaTimeoutException)            => Left(Timeout)
              case Left(e: UnknownTopicOrPartitionException) => Left(Unavailable(s"unknown topic or partition: ${e.getMessage}"))
              case Left(e: RecordTooLargeException)          => Left(WriteFailed(s"record exceeds the broker's max size: ${e.getMessage}"))
              case Left(e: SerializationException)           => Left(WriteFailed(s"serialization failed: ${e.getMessage}"))
              case Left(e)                                   => Left(WriteFailed(e.getMessage))
            }
          }
        }
      }
    }

  // ---- Private implementation ----

  private def checkConnectivity(config: KafkaConfig): IO[Unit] =
    adminClient(config)
      .use(admin => IO.blocking(admin.describeCluster().nodes().get(ConnectivityCheckTimeout.toMillis, TimeUnit.MILLISECONDS)))
      .timeout(ConnectivityCheckTimeout)
      .void

  private def adminClient(config: KafkaConfig): Resource[IO, AdminClient] =
    Resource.fromAutoCloseable(IO.blocking {
      val props = new Properties()
      props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
      props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, ConnectivityCheckTimeout.toMillis.toString)
      AdminClient.create(props)
    })

  private def producerResource(config: KafkaConfig): Resource[IO, KafkaProducer[IO, String, String]] = {
    val settings = ProducerSettings(Serializer[IO, String], Serializer[IO, String])
      .withBootstrapServers(config.bootstrapServers)
      .withAcks(Acks.All)

    KafkaProducer.resource(settings)
  }
}
