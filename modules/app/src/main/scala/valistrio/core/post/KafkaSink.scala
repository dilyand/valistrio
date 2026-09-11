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

/** A [[Sink]] backed by an `fs2-kafka` producer, writing the event's original JSON to `topic`
  * keyed by its event id. A write failure is raised as a
  * [[valistrio.core.ValistrioError.SinkError]] on the IO error channel.
  *
  * The producer is supplied from outside (see [[KafkaSink.producer]]) so the events and DLQ
  * sinks share one connection.
  */
final class KafkaSink(producer: KafkaProducer[IO, String, String], topic: String) extends Sink {
  def write(event: ValidatedEvent): IO[Unit] =
    KafkaSink.produce(producer, topic, event.eventId, event.json.noSpaces)
}

object KafkaSink {

  private val ConnectivityCheckTimeout = 5.seconds

  /** Creates the shared Kafka producer. On acquisition, probes broker connectivity so the app
    * fails fast at startup if Kafka is unreachable rather than only on the first `/post`. The
    * probe checks cluster reachability only (not that the configured topics exist) — topics are
    * commonly provisioned out-of-band, and brokers often disable auto-topic-creation.
    */
  def producer(config: KafkaConfig): Resource[IO, KafkaProducer[IO, String, String]] =
    Resource.eval(checkConnectivity(config)).flatMap(_ => producerResource(config))

  /** Produce one record, mapping transport failures to [[valistrio.core.ValistrioError.SinkError]]
    * on the IO error channel. Shared by the events and DLQ sinks. A `null` key is permitted.
    */
  private[post] def produce(
    producer: KafkaProducer[IO, String, String],
    topic: String,
    key: String,
    value: String
  ): IO[Unit] =
    producer.produceOne_(ProducerRecord(topic, key, value)).flatten.void.adaptError {
      case _: KafkaTimeoutException            => Timeout
      case e: UnknownTopicOrPartitionException => Unavailable(s"unknown topic or partition: ${e.getMessage}")
      case e: RecordTooLargeException          => WriteFailed(s"record exceeds the broker's max size: ${e.getMessage}")
      case e: SerializationException           => WriteFailed(s"serialization failed: ${e.getMessage}")
      case e                                   => WriteFailed(e.getMessage)
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
