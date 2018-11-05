package checkpoint

import java.time.Instant

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import checkpoint.ShardCheckpointTrackerActor.{
  CheckpointIfNeeded,
  Get,
  Process,
  Track,
  WatchCompletion,
  _
}
import software.amazon.kinesis.processor.RecordProcessorCheckpointer
import software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber

import scala.collection.immutable.{Iterable, SortedSet}
import scala.util.Try

class ShardCheckpointTrackerActor(shardId: String,
                                  maxBufferSize: Int,
                                  maxDurationInSeconds: Int)
    extends Actor
    with ActorLogging {
  implicit val ordering =
    Ordering.fromLessThan[ExtendedSequenceNumber]((a, b) => a.compareTo(b) < 0)

  var tracked = SortedSet.empty[ExtendedSequenceNumber]
  var processed = SortedSet.empty[ExtendedSequenceNumber]
  var timeSinceLastCheckpoint = Instant.now().getEpochSecond
  var watchers: List[ActorRef] = List.empty[ActorRef]

  override def receive: Receive = {
    case Track(sequenceNumbers) =>
      log.info("Tracking: {}", sequenceNumbers.map(formatSeqNum).mkString(","))
      tracked ++= sequenceNumbers
      sender() ! Ack
    case Process(sequenceNumber: ExtendedSequenceNumber)
        if tracked.contains(sequenceNumber) =>
      log.info("Marked: {}", formatSeqNum(sequenceNumber))
      processed += sequenceNumber
      sender() ! Ack
      notifyIfCompleted()
    case CheckpointIfNeeded(checkpointer, force) =>
      val checkpointable = tracked.takeWhile(processed.contains)
      log.info("CheckpointIfNeeded: {}",
               checkpointable
                 .map(formatSeqNum)
                 .mkString("[", ",", "]"))
      checkpointable.lastOption.fold(sender() ! Ack) { s =>
        if (shouldCheckpoint() || force) {
          log.info("Checkpointing(forced={}) {}", force, shardId)
          // we absorb the exceptions so we don't lose state for this actor
          Try(
            checkpointer.checkpoint(s.sequenceNumber(), s.subSequenceNumber()))
            .fold(
              ex => sender() ! Failure(ex),
              _ => {
                log.info("{} is at {}", shardId, formatSeqNum(s))
                tracked --= checkpointable
                processed --= checkpointable
                sender() ! Ack
              }
            )
        } else {
          log.info("Skipping Checkpoint")
          sender() ! Ack
        }
      }
      notifyIfCompleted()
    case WatchCompletion =>
      log.info("WatchCompletion")
      watchers = sender() :: watchers
      notifyIfCompleted()

    case Get =>
      log.info("Tracked: {}", tracked.mkString(","))
      log.info("Processed: {}", processed.mkString(","))
    case Shutdown =>
      notifyWatchersOfShutdown()
      context.stop(self)
  }

  override def postStop(): Unit = {
    log.info("Shutting down tracker {}", shardId)
  }

  def shouldCheckpoint(): Boolean = {
    haveReachedMaxTracked() || checkpointTimeElapsed()
  }

  def haveReachedMaxTracked(): Boolean = tracked.size >= maxBufferSize
  def checkpointTimeElapsed(): Boolean = {
    Instant
      .now()
      .getEpochSecond - timeSinceLastCheckpoint >= maxDurationInSeconds
  }

  def notifyIfCompleted() = {
    if (isCompleted() && watchers.nonEmpty) {
      log.info("Notifying completion for {}", shardId)
      watchers.foreach(ref => ref ! Completed)
      watchers = List.empty[ActorRef]
    }
  }

  def notifyWatchersOfShutdown() = {
    if (!isCompleted() && watchers.nonEmpty) {
      log.info("Notifying failure to watchers for {}", shardId)
      watchers.foreach(ref =>
        ref ! Failure(new Exception("Watch failed. Reason: tracker shutdown")))
    } else {
      notifyIfCompleted()
    }
  }

  def isCompleted(): Boolean = {
    tracked.isEmpty || tracked.forall(processed.contains)
  }

  def formatSeqNum(es: ExtendedSequenceNumber): String =
    es.sequenceNumber().takeRight(10)
}

object ShardCheckpointTrackerActor {
  case object Ack
  case class Track(sequenceNumbers: Iterable[ExtendedSequenceNumber])
  case class Process(sequenceNumber: ExtendedSequenceNumber)
  case class CheckpointIfNeeded(checkpointer: RecordProcessorCheckpointer,
                                force: Boolean = false)
  case object WatchCompletion
  case object Completed
  case object Get
  case object Shutdown

  def props(shardId: String,
            maxBufferSize: Int,
            maxDurationInSeconds: Int): Props =
    Props(classOf[ShardCheckpointTrackerActor],
          shardId,
          maxBufferSize,
          maxDurationInSeconds)
}
