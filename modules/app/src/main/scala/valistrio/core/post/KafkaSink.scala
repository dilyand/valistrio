package valistrio.core.post

import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import fs2.kafka._
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig}
import org.apache.kafka.common.errors.{
  RecordTooLargeException,
  SerializationException,
  TimeoutException => KafkaTimeoutException,
  UnknownTopicOrPartitionException
}
import valistrio.core.Config.KafkaConfig
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.domain.ValidatedEvent

import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

/** A [[Sink]] backed by an `fs2-kafka` producer, writing the event's original JSON to
  * `config.topic` keyed by its event id. A write failure is raised as a
  * [[valistrio.core.ValistrioError.SinkError]] on the IO error channel.
  */
final class KafkaSink private (producer: KafkaProducer[IO, String, String], config: KafkaConfig) extends Sink {

  def write(event: ValidatedEvent): IO[Unit] = {
    val record = ProducerRecord(config.topic, event.eventId, event.json.noSpaces)
    producer.produceOne_(record).flatten.void.adaptError {
      case _: KafkaTimeoutException            => Timeout
      case e: UnknownTopicOrPartitionException => Unavailable(s"unknown topic or partition: ${e.getMessage}")
      case e: RecordTooLargeException          => WriteFailed(s"record exceeds the broker's max size: ${e.getMessage}")
      case e: SerializationException           => WriteFailed(s"serialization failed: ${e.getMessage}")
      case e                                   => WriteFailed(e.getMessage)
    }
  }
}

object KafkaSink {

  private val ConnectivityCheckTimeout = 5.seconds

  /** Creates a [[KafkaSink]]. On acquisition, probes broker connectivity so the app fails fast
    * at startup if Kafka is unreachable rather than only on the first `/post`. The probe checks
    * cluster reachability only (not that `config.topic` exists) — topics are commonly provisioned
    * out-of-band, and brokers often disable auto-topic-creation.
    */
  def resource(config: KafkaConfig): Resource[IO, Sink] =
    Resource.eval(checkConnectivity(config)).flatMap { _ =>
      producerResource(config).map(new KafkaSink(_, config))
    }

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
