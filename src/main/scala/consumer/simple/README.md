SimpleConsumer Sample
=================

### Overview ###

fetch messages periodically from kafka.

kafka (for each partition) -> internal queue -> app

### set application.conf ###
    kafka {
      brokers = "your.broker.host" // ex) "host1:9092,host2:9092,..."
      zookeepers = "your.zookeeper.host" // ex) "host1:2181,host2:2181,..."

      topics {
        events {
          name = "your.kafka.topic.name"
          client-id = "consumer.client.id"
        }
      }
    }

### Run ###
    sbt run

### Reference ###
[SimpleConsumer Example](https://cwiki.apache.org/confluence/display/KAFKA/0.8.0+SimpleConsumer+Example)
