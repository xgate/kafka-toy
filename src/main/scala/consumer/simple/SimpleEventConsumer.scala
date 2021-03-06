package consumer.simple

import akka.actor.{Actor, ActorLogging, Props}
import kafka.api.{FetchRequestBuilder, OffsetRequest, PartitionOffsetRequestInfo}
import kafka.cluster.Broker
import kafka.common.{ErrorMapping, TopicAndPartition}
import kafka.consumer.SimpleConsumer
import kafka.message.ByteBufferMessageSet
import kafka.utils.{Utils, ZKStringSerializer, ZkUtils}
import org.I0Itec.zkclient.ZkClient
import org.I0Itec.zkclient.serialize.ZkSerializer

import scala.annotation.tailrec
import scala.collection.mutable

/**
 * kafka SimpleConsumer example
 * - message를 queue에 넣어두고 조금씩 꺼낸다.
 *
 * 1. broker 중에서 리더를 찾는다.
 * 2. 리더에게서 offset을 얻어온다.
 * 3. 리더에게서 message를 얻어온다.
 *
 * Ref. https://cwiki.apache.org/confluence/display/KAFKA/0.8.0+SimpleConsumer+Example
 */
class SimpleEventConsumer extends Actor with ActorLogging {

  import consumer.simple.SimpleEventConsumer._

  val config = context.system.settings.config
  val zookeepers = config.getString("kafka.zookeepers")
  val topic = config.getString("kafka.topics.events.name")
  val clientId = config.getString("kafka.topics.events.client-id")

  log.info("zookeepers:[{}], topic:[{}], clientId:[{}]", zookeepers, topic, clientId)

  val zkClient = createZkClient()
  val brokers = ZkUtils.getAllBrokersInCluster(zkClient)
  var partitionAndLeader = findLeadersForEachPartition
  var partitionAndOffset = fetchOffsetsForEachPartition

  val messageQue = mutable.Queue.empty[String]

  brokers.foreach {
    b => log.info("broker: {}", b.toString())
  }
  partitionAndLeader.foreach {
    case (p, b) => log.info("partition = {}, leaderId = {}", p, b.id)
  }
  partitionAndOffset.foreach {
    case (p, o) => log.info("partition = {}, offset = {}", p, o)
  }

  // initial fetch
  fetchMessagesAndEnqueue()

  def receive = {
    case Fetch(fetchSize) =>
      val messages = fetch(fetchSize)
      sender() ! FetchResult(messages, messages.size)
    case _ =>
      log.error("received unknown message...")
  }

  private def createZkClient(): ZkClient = {
    val zkClient = new ZkClient(zookeepers)
    // serializer 설정 해줘야함
    // Ref. http://qnalist.com/questions/1895977/streamcorruptedexception-when-running-zkutils-getcluster-zkclient
    zkClient.setZkSerializer(new ZkSerializer {
      override def serialize(data: Object): Array[Byte] = {
        ZKStringSerializer.serialize(data)
      }

      override def deserialize(bytes: Array[Byte]): Object = {
        ZKStringSerializer.deserialize(bytes)
      }
    })
    zkClient
  }

  private def fetch(fetchSize: Int): List[String] = {

    @tailrec
    def fetchFromQueue(results: List[String] = Nil): List[String] = {
      if (results.size >= fetchSize) results
      else {
        messageQue.nonEmpty match {
          case true =>
            fetchFromQueue(results :+ messageQue.dequeue())
          case false =>
            log.info("required more messages. try fetch and enqueue ...")
            fetchMessagesAndEnqueue()
            if (messageQue.isEmpty) results
            else fetchFromQueue(results)
        }
      }
    }

    fetchFromQueue()
  }

  private def findLeadersForEachPartition: Map[Int, Broker] = {
    ZkUtils.getPartitionsForTopics(zkClient, Seq(topic)).get(topic) match {
      case Some(partitions) =>
        partitions
          .map(p => (p, findLeaderForPartition(p)))
          .filter(_._2.isDefined)
          .map { case (p, ob) => (p, ob.get) }
          .toMap
      case None => Map.empty[Int, Broker]
    }
  }

  private def findLeaderForPartition(partition: Int): Option[Broker] = {
    ZkUtils.getLeaderForPartition(zkClient, topic, partition) match {
      case Some(leader) =>
        brokers.find(b => b.id == leader)
      case None => None
    }
  }

  private def fetchOffsetsForEachPartition: Map[Int, Long] = {
    partitionAndLeader.map {
      case (partition, broker) =>
        partition -> fetchOffsetForPartition(partition, broker)
    }
  }

  private def fetchOffsetForPartition(partition: Int, broker: Broker): Long = {
    val consumer = createConsumer(broker.host, broker.port)

    val topicAndPartition = new TopicAndPartition(topic, partition)
    val partitionOffsetRequestInfo = new PartitionOffsetRequestInfo(kafka.api.OffsetRequest.EarliestTime, 1)
    val offsetRequest = new OffsetRequest(Map(topicAndPartition -> partitionOffsetRequestInfo))
    val offsetResponse = consumer.getOffsetsBefore(offsetRequest)
    val partitionErrorAndOffsets = offsetResponse.partitionErrorAndOffsets(topicAndPartition)

    consumer.close()

    if (partitionErrorAndOffsets.error == ErrorMapping.NoError)
      partitionErrorAndOffsets.offsets.head
    else
      throw new RuntimeException("failed to fetch offset ...")
  }

  private def createConsumer(host: String, port: Int): SimpleConsumer = {
    val sockTimeout = 10 * 1000
    val bufferSize = 64 * 1024
    new SimpleConsumer(host, port, sockTimeout, bufferSize, clientId)
  }

  private def fetchMessagesAndEnqueue() = {
    partitionAndLeader.foreach {
      case (partition, broker) =>
        messageQue ++= fetchMessages(partition, broker)
    }
  }

  private def fetchMessages(partition: Int, broker: Broker): List[String] = {

    val consumer = createConsumer(broker.host, broker.port)

    val fetchRequest =
      new FetchRequestBuilder()
        .clientId(clientId)
        .addFetch(topic, partition, partitionAndOffset(partition), 10 * 1024) // fetchSize: 읽어드릴 byte 수
        .build()
    val fetchResponse = consumer.fetch(fetchRequest)

    consumer.close()

    fetchResponse.hasError match {
      case true =>
        log.error("fetch response has error....")
        handleMessageFetchError(fetchResponse.errorCode(topic, partition), partition, broker)
      case false =>
        val messageSet = fetchResponse.messageSet(topic, partition)
        log.info("message count for partition <{}> => {}", partition, messageSet.size)
        updateOffset(partition, messageSet)
        extractAsReadableMessages(messageSet)
    }
  }

  private def handleMessageFetchError(errorCode: Short, partition: Int, broker: Broker): List[String] = {
    errorCode match {
      case ErrorMapping.OffsetOutOfRangeCode =>
        log.warning("out of range error...")
        val offset = fetchOffsetForPartition(partition, broker)
        partitionAndOffset = partitionAndOffset + (partition -> offset)
        fetchMessages(partition, broker)
      case code@_ =>
        log.error("it is not out of range error... error code is <{}>...", code)
        throw new RuntimeException("uncontrollable error occurred...")
    }
  }

  private def updateOffset(partition: Int, messageSet: ByteBufferMessageSet) = {
    val oldOffset = partitionAndOffset(partition)
    val newOffset = oldOffset + messageSet.size
    log.info("offset changed for partition <{}>, (<{}> => <{}>)", partition, oldOffset, newOffset)
    partitionAndOffset = partitionAndOffset + (partition -> (oldOffset + messageSet.size))
  }

  private def extractAsReadableMessages(messageSet: ByteBufferMessageSet): List[String] = {
    messageSet.map {
      mo => new String(Utils.readBytes(mo.message.payload), "UTF-8")
    }.toList
  }
}

object SimpleEventConsumer {

  def props = Props[SimpleEventConsumer]

  case class Fetch(fetchSize: Int)

  case class FetchResult(messages: List[String], size: Int)

}
