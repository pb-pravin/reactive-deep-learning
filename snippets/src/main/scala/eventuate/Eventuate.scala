package eventuate

import eventuate.Node.{Ack, InputCommand, WeightedInputCommand}
import akka.actor.{Actor, Props, ActorRef}
import com.rbmhtechnology.eventuate.ConcurrentVersions
import com.rbmhtechnology.eventuate.EventsourcedActor
import com.rbmhtechnology.eventuate.VectorTime
import eventuate.Edge.{UpdatedWeightEvent, UpdateWeightCommand, AddOutputCommand, AddInputCommand}

import scala.util.{Failure, Success}

object Eventuate {

}
object Node {
  case class InputCommand(feature: Double)
  case class WeightedInputCommand(feature: Double, weight: Double)

  case class AddInputsCommand(input: Seq[ActorRef])
  case class AddOutputsCommand(output: Seq[ActorRef])
  case object Ack

  case class UpdateBiasCommand(bias: Double)
  case class UpdatedBiasEvent(bias: Double)
}

object Edge {
  case class AddInputCommand(input: ActorRef)
  case class AddOutputCommand(output: ActorRef)

  case class UpdateWeightCommand(weight: Double)
  case class UpdatedWeightEvent(weight: Double)

  def props(aggregateId: Option[String], replicaId: String, eventLog: ActorRef): Props =
    Props(new Edge(aggregateId, replicaId, eventLog))
}

trait HasInput extends Actor {
  var input: ActorRef = _

  def addInput(): Receive = {
    case AddInputCommand(i) =>
      input = i
      sender() ! Ack
  }
}

trait HasOutput extends Actor {
  var output: ActorRef = _

  def addOutput(): Receive = {
    case AddOutputCommand(o) =>
      output = o
      sender() ! Ack
  }
}

class Edge(
    override val aggregateId: Option[String],
    override val replicaId: String,
    override val eventLog: ActorRef) extends EventsourcedActor with HasInput with HasOutput {

  var weight: Double = 0.3

  override def onCommand: Receive = run orElse addInput orElse addOutput

  private var versionedState: ConcurrentVersions[Double, Double] =
    ConcurrentVersions(0.3, (s, a) => a)

  override def onEvent: Receive = {
    case UpdatedWeightEvent(w) =>
      versionedState = versionedState.update(w, lastVectorTimestamp, lastEmitterReplicaId)
      if (versionedState.conflict) {
        println(s"Conflicting versions on replica $replicaId " + versionedState.all.map(v => s"value ${v.value} vector clock ${v.updateTimestamp} emitted by replica ${v.emitterReplicaId}"))
        val conflictingVersions = versionedState.all
        val avg = conflictingVersions.map(_.value).sum / conflictingVersions.size

        val newTimestamp = conflictingVersions.map(_.updateTimestamp).foldLeft(VectorTime())(_.merge(_))
        versionedState.update(avg, newTimestamp, replicaId)
        versionedState = versionedState.resolve(newTimestamp)

        weight = versionedState.all.head.value
        println(s"Conflicting versions on replica $replicaId resolved " + versionedState.all.map(v => s"value ${v.value} vector clock ${v.updateTimestamp}"))
      } else {
        weight = versionedState.all.head.value
      }
  }

  def run: Receive = {
    case InputCommand(f) =>
      output ! WeightedInputCommand(f, weight)
    //println(s"AggregateId $aggregateId replicaId $replicaId has weight $weight")
    case UpdateWeightCommand(w) =>
      persist(UpdatedWeightEvent(w)) {
        case Success(evt) => onEvent(evt)
        case Failure(e) =>
      }
  }
}