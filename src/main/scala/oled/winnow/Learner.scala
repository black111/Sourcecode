package oled.winnow

import java.io.File

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import app.runutils.IOHandling.Source
import app.runutils.{Globals, RunningOptions}
import logic.Examples.Example
import logic.{Literal, LogicUtils, Theory}
import oled.winnow.MessageTypes.{FinishedBatchMsg, ProcessBatchMsg}
import org.slf4j.LoggerFactory
import oled.functions.SingleCoreOLEDFunctions.eval

import scala.collection.mutable.Map
import AuxFuncs._
import utils.{ASP, Utils}
import utils.Implicits._

import scala.reflect.internal.Trees


/**
  * Created by nkatz at 26/10/2018
  */

class Learner[T <: Source](val inps: RunningOptions,
                           val trainingDataOptions: T,
                           val testingDataOptions: T,
                           val trainingDataFunction: T => Iterator[Example],
                           val testingDataFunction: T => Iterator[Example]) extends Actor {

  startTime = System.nanoTime()

  private var totalBatchProcessingTime = 0.0
  private var totalRuleScoringTime = 0.0
  private var totalNewRuleTestTime = 0.0
  private var totalCompressRulesTime = 0.0
  private var totalExpandRulesTime = 0.0
  private var totalNewRuleGenerationTime = 0.0

  private val logger = LoggerFactory.getLogger(self.path.name)

  private val withec = Globals.glvalues("with-ec").toBoolean

  // Control learning iterations over the data
  private var repeatFor = inps.repeatFor

  // Local data variable. Cleared at each iteration (in case repfor > 1).
  private var data = Iterator[Example]()

  // This is optionlal. A testing set (for holdout evaluation) may not be provided.
  private var testingData = Iterator[Example]()

  // Counts the number of precessed batches. Used to determine when to
  // perform holdout evaluation on the test set. Incremented whenever a
  // new batch is fetched (see the getNextBatch() method)
  private var batchCounter = 0

  // Stores the error from the prequential evaluation at each batch.
  private var prequentialError = Vector[Double]()

  // Current prequential error (for logging only, updated as a string message containing the actual error).
  private var currentError = ""

  // Stores the F1-scores from holdout evaluation
  private var holdoutScores = Vector[Double]()

  // Evolving theory. If we're learning with the Event Calculus the head of the
  // list is the initiation part of the theory and the tail is the termination.
  // If not, the list has a single element (the current version of the theory).
  private var theory = if (withec) List(Theory(), Theory()) else List(Theory())


  private var startTime = System.nanoTime()

  private var endTime = System.nanoTime()

  // Get the training data from the current inout source
  private def getTrainData = trainingDataFunction(trainingDataOptions)

  private def getTestingData = testingDataFunction(testingDataOptions)

  private def getNextBatch = {
    this.batchCounter += 1
    if (data.isEmpty) Example() else data.next()
  }


  val workers: List[ActorRef] = {

    // Two workers for initiated and terminated rules respectively.
    if (withec) {
      val worker1 = context.actorOf(Props( new Worker(inps) ), name = "worker-1")
      val worker2 = context.actorOf(Props( new Worker(inps) ), name = "worker-2")
      List(worker1, worker2)
    } else {
      val worker = context.actorOf(Props( new Worker(inps) ), name = "worker")
      List(worker)
    }
  }

  // Use this variable to count the responses received from worker actors while processing a new batch.
  private var responseCounter = workers.length

  // Keep response messages from workers in here until all workers are done.
  private val responses = Map[String, FinishedBatchMsg]()

  def receive = {

    case "start" => {
      this.repeatFor -= 1
      this.data = getTrainData
      if (inps.test != "None") this.testingData = getTestingData
      if (this.data.isEmpty) {
        logger.error(s"Input source ${inps.train} is empty.")
        System.exit(-1)
      }
      processNext()
    }

    case "eval" => {
      // Prequential evaluation of a given theory
      logger.info(s"Performing prequential Evaluation of theory from ${inps.evalth}")
      (1 to repeatFor) foreach { _ =>
        this.data = getTrainData
        while (data.hasNext) {
          evaluate(data.next(), inps.evalth)
          logger.info(currentError)
        }
      }
      logger.info(s"Prequential error vector:\n${prequentialError.mkString(",")}")
      logger.info(s"Prequential error vector (Accumulated Error):\n${prequentialError.scanLeft(0.0)(_ + _).tail}")
      context.system.terminate()
    }

    case p: FinishedBatchMsg => {
      responseCounter -= 1
      if (p.targetClass == "") responses += ("theory-no-ec" -> p) else responses += (p.targetClass -> p)
      if (responseCounter == 0) {

        // General case first (no event calculus)
        if (responses.keySet.size == 1) {
          val r = responses("theory-no-ec")
          this.theory = List(r.theory)
          this.totalBatchProcessingTime += r.BatchProcessingTime
          this.totalCompressRulesTime += r.compressRulesTime
          this.totalExpandRulesTime += r.expandRulesTime
          this.totalNewRuleGenerationTime += r.newRuleGenerationTime
          this.totalNewRuleTestTime += r.newRuleTestTime
          this.totalRuleScoringTime += r.ruleScoringTime
        } else {
          val ir = responses("initiated")
          val tr = responses("terminated")
          val newInitTheory = ir.theory
          val newTermTheory = tr.theory
          this.theory = List(newInitTheory, newTermTheory)
          this.totalBatchProcessingTime += math.max(ir.BatchProcessingTime, tr.BatchProcessingTime)
          this.totalCompressRulesTime += math.max(ir.compressRulesTime, tr.compressRulesTime)
          this.totalExpandRulesTime += math.max(ir.expandRulesTime, tr.expandRulesTime)
          this.totalNewRuleGenerationTime += math.max(ir.newRuleGenerationTime, tr.newRuleGenerationTime)
          this.totalNewRuleTestTime += math.max(ir.newRuleTestTime, tr.newRuleTestTime)
          this.totalRuleScoringTime += math.max(ir.ruleScoringTime, tr.ruleScoringTime)
        }
        //logger.info(currentError)
        // reset these before processing a new batch
        responseCounter = workers.length
        responses.clear()
        processNext()
      }
    }
  }

  /*
  * Performs online evaluation and sends the next batch to the worker(s) for processing.
  *
  * */
  private def processNext() = {

    val nextBatch = getNextBatch

    if (nextBatch.isEmpty) {
      logger.info(s"Finished the data.")
      if (this.repeatFor > 0) {
        logger.info(s"Starting new iteration.")
        self ! "start"
      } else if (this.repeatFor == 0) {

        endTime = System.nanoTime()
        logger.info("Done.")
        workers foreach(w => w ! PoisonPill)
        wrapUp()
        context.system.terminate()

      } else {
        throw new RuntimeException("This should never have happened (repeatfor is now negative?)")
      }
    } else {
      //evaluate(nextBatch)
      evaluateTest(nextBatch)
      if (this.workers.length > 1) { // we're learning with the Event Calculus.
        val msg1 = new ProcessBatchMsg(theory.head, nextBatch, "initiated")
        val msg2 = new ProcessBatchMsg(theory.tail.head, nextBatch, "terminated")
        workers.head ! msg1
        workers.tail.head ! msg2
      } else { // We're learning without the Event Calculus.
        workers.head ! new ProcessBatchMsg(theory.head, nextBatch)
      }
    }

  }

  /* Finished. Just show results and shut down */
  def wrapUp() = {
    val merged = {
      if (theory.length == 1) {
        theory.head
      } else {
        Theory(theory.head.clauses ++ theory.tail.head.clauses)
      }
    }

    val theorySize = merged.clauses.foldLeft(0)((x,y) => x + y.body.length + 1)
    val totalRunningTime = (endTime - startTime)/1000000000.0
    val totalTrainingTime = totalBatchProcessingTime

    /* THIS MAY TAKE TOO LONG FOR LARGE AND COMPLEX THEORIES!! */
    logger.info("Compressing theory...")
    val merged_ = Theory(LogicUtils.compressTheory(merged.clauses))

    logger.info(s"\nTheory found:\n ${merged_.showWithStats}")
    logger.info(s"Theory size: $theorySize")
    logger.info(s"Total running time: $totalTrainingTime")
    logger.info(s"Total batch processing time: $totalRunningTime")
    logger.info(s"Total rule scoring time: $totalRuleScoringTime")
    logger.info(s"Total rule expansion time: $totalExpandRulesTime")
    logger.info(s"Total rule compression time: $totalCompressRulesTime")
    logger.info(s"Total testing for new rule generation time: $totalNewRuleTestTime")
    logger.info(s"Total new rule generation  time: $totalNewRuleGenerationTime")

    logger.info(s"Prequential error vector:\n${prequentialError.mkString(",")}")
    logger.info(s"Prequential error vector (Accumulated Error):\n${prequentialError.scanLeft(0.0)(_ + _).tail}")

    logger.info(s"Holdout error vector:\nTODO")
  }

  implicit class ExtendedDouble(n: Double) {
    def rounded(x: Int) = {
      val w = math.pow(10, x)
      (n * w).toLong.toDouble / w
    }
  }


  def evaluate(batch: Example, inputTheoryFile: String = "") = {
    // prequential first
    if (withec) {
      val (init, term) = (theory.head, theory.tail.head)

      val merged = Theory( (init.clauses ++ term.clauses).filter(p => p.body.length >= 1 && p.seenExmplsNum > 5000 && p.score > 0.9) )

      //val merged = Theory( init.clauses ++ term.clauses )

      val (tps, fps, fns, precision, recall, fscore) = eval(merged, batch, inps)

      // I think this is wrong, the correct error is the number of mistakes (fps+fns)
      //currentError = s"TPs: $tps, FPs: $fps, FNs: $fns, error (|true state| - |inferred state|): ${math.abs(batch.annotation.toSet.size - (tps+fps))}"

      currentError = s"Number of mistakes (FPs+FNs) "
      this.prequentialError = this.prequentialError :+ (fps+fns).toDouble

    }

    // TODO :
    // Implement holdout evaluation.
  }


  /* Performs online evaluation. Prequential is always performed.
   * Holdout is  performed every 1000 examples if the testing set is non-empty.
   */
  def evaluateTest(batch: Example, inputTheoryFile: String = "") = {

    // prequential first
    if (withec) {
      val (init, term) = (theory.head, theory.tail.head)

      //val merged = Theory( (init.clauses ++ term.clauses).filter(p => p.body.length >= 1 && p.seenExmplsNum > 5000 && p.score > 0.9) )

      val merged = Theory( init.clauses ++ term.clauses )

      //------------------
      // TEST STUFF START
      //------------------

      if (theory.head.clauses.nonEmpty && theory.tail.head.clauses.nonEmpty) {

        merged.clauses foreach (rule => if (rule.refinements.isEmpty) rule.generateCandidateRefs)
        //val t = merged.clauses.flatMap(x => x.refinements :+ x)

        val _marked = marked(merged.clauses.toVector, inps.globals)

        // QUICK AND DIRTY SOLUTION JUST TO TRY IT.
        println(batch.time+" Test eval for winnow")

        val e = (batch.annotationASP ++ batch.narrativeASP).mkString("\n")
        val markedProgram = _marked._1
        val markedMap = _marked._2
        val all = e + markedProgram + "\n#include \"/home/nkatz/dev/OLED-BK/BKExamples/BK-various-taks/DevTest/caviar-bk/bk.lp\"." + "\n#show marked/2."
        val f = Utils.getTempFile(s"quick-and-dirty",".lp")
        Utils.writeToFile(f, "append")(p => List(all) foreach p.println)
        val path = f.getCanonicalPath
        val answerSet = ASP.solve(task = Globals.SCORE_RULES, aspInputFile = new File(path))
        val atoms = if (answerSet.nonEmpty) answerSet.head.atoms.toSet else Set[String]()

        val inferred_temp = atoms.map{ a =>
           val l = Literal.parse(a)
          (l.terms.head.tostring, l.terms.tail.head.tostring)
        }.groupBy(z => z._2).map(z =>  (z._1, z._2.map(_._1)) )

        val initRules = theory.head.clauses
        val initRulesNum = (initRules ++ initRules.flatMap(_.refinements)).length

        val termRules = theory.tail.head.clauses
        val termRulesNum = (termRules ++ termRules.flatMap(_.refinements)).length

        val inferred_final = inferred_temp.foldLeft(Set[String]()) { (accum, y) =>
          val (atom, ruleIds) = (y._1, y._2)
          val weightSum = ruleIds.map(id => markedMap(id).w).sum
          //val majority = if (atom.contains("initiated")) initRulesNum else termRulesNum
          val majority = 16000
          if (weightSum >= majority) {
            accum + atom
          } else {
            accum
          }
        }

        //println(inferred_final)

        val evalProgram =
          (batch.narrative.toSet ++ inferred_final).map(x => x+".").mkString("\n")+
          inps.globals.INCLUDE_BK(inps.globals.BK_CROSSVAL)+"\nout(holdsAt(F,T)) :- holdsAt(F,T), fluent(F).\n#show.\n#show out/1."
        val f1 = Utils.getTempFile("isConsistent",".lp")
        Utils.writeLine(evalProgram, f1, "overwrite")

        val inferredState = ASP.solve(task = Globals.INFERENCE, aspInputFile = f1)

        val _inferred = if (inferredState.nonEmpty) inferredState.head.atoms.toSet else Set[String]()
        val inferred = _inferred.map(x => Literal.parse(x).terms.head.tostring)

        val trueAtoms = batch.annotation.toSet

        val (tps, fps, fns) =
        if (inferredState.nonEmpty) {
          val _tps = inferred.intersect(trueAtoms).size
          val _fps = inferred.diff(trueAtoms).size
          val _fns = trueAtoms.diff(inferred).size
          (_tps, _fps, _fns)
        } else {
          (0, 0, batch.annotation.size)
        }

        currentError = s"Number of mistakes (FPs+FNs) "
        this.prequentialError = this.prequentialError :+ (fps+fns).toDouble

        println(prequentialError)

        //------------------
        // TEST STUFF END
        //------------------


        val stop = "stop"

        // QUICK AND DIRTY SOLUTION JUST TO TRY IT.
      }


    }

    // TODO :
    // Implement holdout evaluation.
  }



}
