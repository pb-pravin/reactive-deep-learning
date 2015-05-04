import Node._
import akka.typed.ActorRef
import akka.typed.ScalaDSL.{Or, Static}

object Perceptron {
  def props(): Props = Props[Perceptron]
}

class Perceptron() extends Neuron {

  override var activationFunction: Double => Double = Neuron.sigmoid
  override var bias: Double = 0.1

  var weightsT: Seq[Double] = Seq()
  var featuresT: Seq[Double] = Seq()

  def receive = Or(Or(run, addInput), addOutput)

  private def allInputsAvailable(w: Seq[Double], f: Seq[Double], in: Seq[ActorRef[Nothing]]) =
    w.length == in.length && f.length == in.length

  def run = Static[WeightedInput] { msg =>
      featuresT = featuresT :+ msg.feature
      weightsT = weightsT :+ msg.weight

      if(allInputsAvailable(weightsT, featuresT, inputs)) {
        val activation = activationFunction(weightsT.zip(featuresT).map(x => x._1 * x._2).sum + bias)

        featuresT = Seq()
        weightsT = Seq()

        outputs.foreach(_ ! Input(activation))
      }
  }
}