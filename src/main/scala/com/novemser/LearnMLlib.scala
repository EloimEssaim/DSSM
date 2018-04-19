package com.novemser

import com.novemser.util.Timer
import org.apache.spark.SparkConf
import org.apache.spark.ml.classification._
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature._
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.tree.impl.Utils
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import scala.collection.mutable

trait Type

case class SER() extends Type {
  override def toString: String = "SER"
}

case class STRUT() extends Type {
  override def toString: String = "STRUT"
}

case class DSSM() extends Type {
  override def toString: String = "DSSM"
}

object LearnMLlib {
  private val conf = new SparkConf()
    .setAppName("Test App")
    .setMaster("local[16]")
  private val spark = SparkSession
    .builder()
    .config(conf)
    .getOrCreate()
  spark.sparkContext.setLogLevel("WARN")

  def simple(): Unit = {
    // Prepare training data from a list of (label, features) tuples.
    val training = spark
      .createDataFrame(
        Seq(
          (1.0, Vectors.dense(0.0, 1.1, 0.1)),
          (0.0, Vectors.dense(2.0, 1.0, -1.0)),
          (0.0, Vectors.dense(2.0, 1.3, 1.0)),
          (1.0, Vectors.dense(0.0, 1.2, -0.5))
        )
      )
      .toDF("label", "features")

    val lr = new LogisticRegression()
    // Print out the parameters, documentation, and any default values.
    //    println(s"LogisticRegression parameters:\n ${lr.explainParams()}\n")
    lr.setMaxIter(10)
      .setRegParam(0.01d)

    val model1 = lr.fit(training)
    println(s"Model 1 was fit using parameters: ${model1.parent.extractParamMap}")

    // We may alternatively specify parameters using a ParamMap,
    // which supports several methods for specifying parameters.
    val paramMap = ParamMap(lr.maxIter -> 20)
      .put(lr.maxIter, 30) // Specify 1 Param. This overwrites the original maxIter.
      .put(lr.regParam -> 0.1, lr.threshold -> 0.55) // Specify multiple Params.

    // Combine ParamMaps
    val paramMap2 = ParamMap(lr.probabilityCol -> "My prob")
    val paramMapCombined = paramMap ++ paramMap2
    val model2 = lr.fit(training, paramMapCombined)

    // Prepare test data.
    val test = spark
      .createDataFrame(
        Seq(
          (1.0, Vectors.dense(-1.0, 1.5, 1.3)),
          (0.0, Vectors.dense(3.0, 2.0, -0.1)),
          (1.0, Vectors.dense(0.0, 2.2, -1.5))
        )
      )
      .toDF("label", "features")

    // Using transformer.transform() to make predictions
    model2
      .transform(test)
      .select("features", "label", "My Prob", "prediction")
      .collect()
      .foreach {
        case Row(features: Vector, label: Double, prob: Vector, prediction: Double) =>
          println(s"($features, $label) -> prob=$prob, prediction=$prediction")
      }
  }

  def pipeline(): Unit = {
    val training = spark
      .createDataFrame(
        Seq(
          (0L, "a b c d e spark", 1.0),
          (1L, "b d", 0.0),
          (2L, "spark c f g", 1.0),
          (3L, "hadoop mapreduce", 0.0)
        )
      )
      .toDF("id", "text", "label")

    // Configure an ML pipeline, which consists of three stages: tokenizer, hashingTF, and lr.
    val tokenizer = new Tokenizer()
      .setInputCol("text")
      .setOutputCol("words")

    val hashingTF = new HashingTF()
      .setNumFeatures(1000)
      .setInputCol(tokenizer.getOutputCol)
      .setOutputCol("features")

    val lr = new LogisticRegression()
      .setMaxIter(10)
      .setRegParam(0.001)

    val pipline = new Pipeline()
      .setStages(Array(tokenizer, hashingTF, lr))

    // Fit the pipeline to training docs.
    val model = pipline.fit(training)

    model.write.overwrite().save("/tmp/spark-lr-model")
    val sameModel = PipelineModel.load(
      "/tmp/spark-lr-model"
    )

    // Prepare test documents, which are unlabeled (id, text) tuples.
    val test = spark
      .createDataFrame(
        Seq(
          (4L, "spark i j k"),
          (5L, "l m n"),
          (6L, "spark hadoop spark"),
          (7L, "apache hadoop")
        )
      )
      .toDF("id", "text")

    model
      .transform(test)
      .select("id", "text", "probability", "prediction")
      .collect()
      .foreach {
        case Row(id, text, pb, pred) =>
          println(s"($id, $text) --> prob=$pb, prediction=$pred")
      }
  }

  def testDT(): Unit = {
    val data = spark.read
      .format("libsvm")
      .load("/home/novemser/Documents/Code/spark/data/mllib/sample_libsvm_data.txt")

    //    val training = spark.createDataFrame(Seq(
    //      (0L, "a b c d e spark", 1.0),
    //      (1L, "b d", 0.0),
    //      (2L, "spark c f g", 1.0),
    //      (3L, "hadoop mapreduce", 0.0)
    //    )).toDF("id", "text", "label")
    // Index labels, adding metadata to the label column.
    // Fit on whole dataset to include all labels in index.
    val labelIndexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("indexedLabel")
      .fit(data)

    // Automatically identify categorical features, and index them.
    val featureIndexer = new VectorIndexer()
      .setInputCol("features")
      .setOutputCol("indexedFeatures")
      .setMaxCategories(4) // features with > 4 distinct values are treated as continuous.
      .fit(data)
    // Split the data into training and test sets (30% held out for testing).
    val Array(trainingData, testData) = data.randomSplit(Array(0.7, 0.3))

    // Configure an ML pipeline, which consists of three stages: tokenizer, hashingTF, and lr.
    //    val tokenizer = new Tokenizer()
    //      .setInputCol("text")
    //      .setOutputCol("words")
    //    val hashingTF = new HashingTF()
    //      .setNumFeatures(1000)
    //      .setInputCol(tokenizer.getOutputCol)
    //      .setOutputCol("features")
    val dt = new CustomDecisionTreeClassifier()
      .setLabelCol("indexedLabel")
      .setFeaturesCol("indexedFeatures")
    // Convert indexed labels back to original labels.
    val labelConverter = new IndexToString()
      .setInputCol("prediction")
      .setOutputCol("predictedLabel")
      .setLabels(labelIndexer.labels)

    //    val pipeline = new Pipeline()
    //      .setStages(Array(tokenizer, hashingTF, dt))
    // Chain indexers and tree in a Pipeline.
    val pipeline = new Pipeline()
      .setStages(Array(labelIndexer, featureIndexer, dt, labelConverter))

    //    val model = pipeline.fit(data)
    //    val test = spark.createDataFrame(Seq(
    //      (4L, "spark i j k"),
    //      (5L, "l m n"),
    //      (6L, "spark hadoop spark"),
    //      (7L, "apache hadoop")
    //    )).toDF("id", "text")
    // Train model. This also runs the indexers.
    val model = pipeline.fit(trainingData)

    // Make predictions.
    val predictions = model.transform(testData)
    // Select example rows to display.
    predictions.select("predictedLabel", "label", "features").show(5)

    // Select (prediction, true label) and compute test error.
    val evaluator = new MulticlassClassificationEvaluator()
      .setLabelCol("indexedLabel")
      .setPredictionCol("prediction")
      .setMetricName("accuracy")
    val accuracy = evaluator.evaluate(predictions)
    println(s"Test Error = ${1.0 - accuracy}")

    val treeModel = model.stages(2).asInstanceOf[DecisionTreeClassificationModel]
    println(s"Learned classification tree model:\n ${treeModel.toDebugString}")

  }

  def testWine(): Unit = {
    val red = spark.read
      .option("header", "true")
      .option("inferSchema", value = true)
      .csv("src/main/resources/wine/red.csv")

    val white = spark.read
      .option("header", "true")
      .option("inferSchema", value = true)
      .csv("src/main/resources/wine/white.csv")

    doCrossValidateExperiment(white, red, treeType = SER(), expName = "WineSER-White-Red")
    doCrossValidateExperiment(white, red, treeType = STRUT(), expName = "WineSTRUT-White-Red")
    doCrossValidateExperiment(red, white, treeType = SER(), expName = "WineSER-Red-White")
    doCrossValidateExperiment(red, white, treeType = STRUT(), expName = "WineSTRUT-Red-White")
    doCrossValidateExperiment(white, white, treeType = STRUT(), expName = "WineSTRUT-White-Red")
    doCrossValidateExperiment(red, red, treeType = SER(), expName = "WineSER-Red-White")
  }

  def testNumeric(): Unit = {
    val data = spark.read
      .option("header", "true")
      .option("inferSchema", value = true)
      .csv("src/main/resources/simple/numeric.csv")

    doCrossValidateExperiment(data, data, numTrees = 1, treeType = STRUT())
  }

  def testMushroom(): Unit = {
    val data = spark.read
      .option("header", "true")
      .option("inferSchema", value = true)
      .csv("src/main/resources/mushroom/mushroom.csv")

    val shapeE = data.filter("`stalk-shape` = 'e'") // 3516
    val shapeT = data.filter("`stalk-shape` = 't'") // 4608

    val indexers = mutable.ArrayBuffer[StringIndexerModel]()
    data.schema
      .map(_.name)
      .filter(_ != "id")
      .filter(_ != "class")
      .foreach((name: String) => {
        val stringIndexer = new StringIndexer()
          .setInputCol(name)
          .setHandleInvalid("keep")
          .setOutputCol(s"indexed_$name")
          .fit(shapeE)
        indexers += stringIndexer
      })

    val trainLabelIndexer = new StringIndexer()
      .setHandleInvalid("skip")
      .setInputCol("class")
      .setOutputCol("label")
      .fit(shapeE)

    val transferLabelIndexer = new StringIndexer()
      .setHandleInvalid("skip")
      .setInputCol("class")
      .setOutputCol("label")
      .fit(shapeT)

    val trainAssembler = new VectorAssembler()
      .setInputCols(indexers.map(_.getOutputCol).toArray)
      .setOutputCol("features")

    val transferAssembler = trainAssembler

    val rf = new SourceRandomForestClassifier()
    rf.setFeaturesCol { trainAssembler.getOutputCol }
      .setLabelCol { trainLabelIndexer.getOutputCol }
      .setNumTrees(1)

    val labelConverter = new IndexToString()
      .setInputCol("prediction")
      .setOutputCol("predictedLabel")
      .setLabels(trainLabelIndexer.labels)

    val timer = new Timer()
      .initTimer("srcTrain")
      .initTimer("transferTrain")

    val pipeline = new Pipeline()
      .setStages(indexers.toArray ++ Array(trainLabelIndexer, trainAssembler, rf, labelConverter))
    // Train model. This also runs the indexers.
    val srcAcc = Utils.trainAndTest(pipeline, shapeE, shapeT, withBErr = false, timer, "srcTrain")

    val ser = new SERClassifier(rf.model)
      .setFeaturesCol { trainAssembler.getOutputCol }
      .setLabelCol { trainLabelIndexer.getOutputCol }

    val transferPipeline = new Pipeline()
      .setStages(
        indexers.toArray ++ Array(transferLabelIndexer, transferAssembler, ser, labelConverter)
      )

    val transferAcc = Utils.trainAndTest(transferPipeline, shapeT, shapeT, withBErr = false, timer, "transferTrain")
    println(s"SrcOnly err:$srcAcc, strut err:$transferAcc")
  }

  def testDigits(): Unit = {
    val d6 = spark.read
      .option("header", "true")
      .option("inferSchema", value = true)
      .csv("src/main/resources/digits/optdigits_6.csv")

    val d9 = spark.read
      .option("header", "true")
      .option("inferSchema", value = true)
      .csv("src/main/resources/digits/optdigits_9.csv")

    doCrossValidateExperiment(d6, d9, treeType = SER(), expName = "DigitsSER-6-9")
    doCrossValidateExperiment(d6, d9, treeType = STRUT(), expName = "DigitsSER-6-9")
    doCrossValidateExperiment(d9, d6, treeType = SER(), expName = "DigitsSER-9-6")
    doCrossValidateExperiment(d9, d6, treeType = STRUT(), expName = "DigitsSER-9-6")
  }

  def testLandMine(): Unit = {
    val mine = mutable.ArrayBuffer[DataFrame]()
    Range(1, 30)
      .map(i => s"src/main/resources/landMine/minefield$i.csv")
      .foreach(path => {
        val data = spark.read
          .option("header", "true")
          .option("inferSchema", value = true)
          .csv(path)
        mine += data
      })

    val source = mine.take(15).reduce { _ union _ }
    val data = mine.takeRight(14)

    val res = Range(0, 14)
      .map { _ =>
        {
          val target = data.remove(0)
          val test = data.reduce { _ union _ }
          val (srcAcc, serAcc) = doExperiment(source, target, test, berr = true, treeType = SER())
          val (_, strutAcc) = doExperiment(source, target, test, berr = true, treeType = STRUT())
          data += target
          (srcAcc, serAcc, strutAcc)
        }
      }
      .reduce { (l, r) =>
        (l._1 + r._1, l._2 + r._2, l._3 + r._3)
      }
    println(s"src acc:${res._1 / 14}, ser acc:${res._2 / 14}, strut acc:${res._3 / 14}")
  }

  def testLetter(): Unit = {
    val data = spark.read
      .option("header", "true")
      .option("inferSchema", true)
      .csv("src/main/resources/letter/letter-recognition.csv")

    val x2barmean = data.groupBy("class").agg("x2bar" -> "mean").collect().sortBy(_.getString(0))

    val filterFunc: Row => Boolean = row => {
      val x2bar = row.getInt(7)
      val mean = x2barmean
        .filter(keyMean => keyMean.getString(0).equalsIgnoreCase(row.getString(16)))
        .head
        .getDouble(1)
      x2bar <= mean
    }

    val x2barGMean = data.filter(r => !filterFunc(r))
    val x2barLEMean = data.filter(filterFunc)

    doCrossValidateExperiment(x2barLEMean, x2barGMean, expName = "LetterSER-x2bar<=mean-x2bar>mean", treeType = SER())
    doCrossValidateExperiment(
      x2barLEMean,
      x2barGMean,
      expName = "LetterSTRUT-x2bar<=mean-x2bar>mean",
      treeType = STRUT()
    )
    doCrossValidateExperiment(x2barGMean, x2barLEMean, expName = "LetterSER-x2bar>mean-x2bar<=mean", treeType = SER())
    doCrossValidateExperiment(
      x2barGMean,
      x2barLEMean,
      expName = "LetterSTRUT-x2bar>mean-x2bar<=mean",
      treeType = STRUT()
    )
  }

  def doCrossValidateExperiment(source: DataFrame,
                                target: DataFrame,
                                berr: Boolean = false,
                                numTrees: Int = 50,
                                treeType: Type = SER(),
                                maxDepth: Int = 10,
                                expName: String = "",
                                doTransfer: Boolean = false): (Double, Double) = {
    println(s"----------------------------------$expName--------------------------------------")
    val expData = MLUtils.kFold(target.rdd, 20, 1)
    val timer = new Timer()
      .initTimer("src")
      .initTimer("transfer")
    source.persist()
    target.persist()
    val count = expData.length
    val result = expData
      .map { data =>
        {
          val train = spark.createDataFrame(data._1, target.schema)
          val test = spark.createDataFrame(data._2, target.schema)
          doExperiment(source, train, test, berr, numTrees, treeType, maxDepth, timer)
        }
      }
      .reduce { (l, r) => // average
        (l._1 + r._1, l._2 + r._2)
      }
    println(s"CV src result:${result._1 / count}, transfer result:${result._2 / count}")
    timer.printTime()
    println(s"--------------------------------------------------------------------------------")
    result
  }

  def doExperiment(source: DataFrame,
                   target: DataFrame,
                   test: DataFrame,
                   berr: Boolean = false,
                   numTrees: Int = 50,
                   treeType: Type = SER(),
                   maxDepth: Int = 10,
                   timer: Timer = new Timer): (Double, Double) = {
//    printInfo(source, target, test)

    val trainLabelIndexer = new StringIndexer()
      .setHandleInvalid("skip")
      .setInputCol("class")
      .setOutputCol("label")
      .setStringOrderType("alphabetAsc")
      .fit(source)

    val transferLabelIndexer = new StringIndexer()
      .setHandleInvalid("skip")
      .setInputCol("class")
      .setOutputCol("label")
      .setStringOrderType("alphabetAsc")
      .fit(target)

    val trainAssembler = new VectorAssembler()
      .setInputCols(source.schema.map(_.name).filter(s => s != "class").toArray)
      .setOutputCol("features")

    val transferAssembler = trainAssembler

    val rf = new SourceRandomForestClassifier()
    rf.setFeaturesCol { trainAssembler.getOutputCol }
      .setLabelCol { trainLabelIndexer.getOutputCol }
      .setMaxDepth(maxDepth)
      .setNumTrees(numTrees)
      .setImpurity("entropy")

    // Convert indexed labels back to original labels.
    val labelConverter = new IndexToString()
      .setInputCol("prediction")
      .setOutputCol("predictedLabel")
      .setLabels(trainLabelIndexer.labels)

    val trainPipeline = new Pipeline()
      .setStages(Array(trainLabelIndexer, trainAssembler, rf, labelConverter))

    val srcAcc = Utils.trainAndTest(trainPipeline, source, test, berr, timer, "src")

    val classifier = treeType match {
      case SER()   => new SERClassifier(rf.model)
      case STRUT() => new STRUTClassifier(rf.model)
      case _       => null
    }

    classifier
      .setFeaturesCol { trainAssembler.getOutputCol }
      .setLabelCol { trainLabelIndexer.getOutputCol }
      .setMaxDepth { maxDepth }

    val transferPipeline = new Pipeline()
      .setStages(Array(transferLabelIndexer, transferAssembler, classifier, labelConverter))

    val transferAcc = Utils.trainAndTest(transferPipeline, target, test, berr, timer, "transfer")
    println(s"SrcOnly err:$srcAcc, $treeType err:$transferAcc")
    // Using b error mentioned in paper
    if (!berr) {
      (srcAcc._1, transferAcc._1)
    } else {
      (srcAcc._2, transferAcc._2)
    }
  }

  def testStrut(): Unit = {
    def testBug(): Unit = {
      val data = spark.read
        .option("header", "true")
        .option("inferSchema", true)
        .csv("src/main/resources/letter/letter-recognition.csv")

      val x2barmean = data.groupBy("class").agg("x2bar" -> "mean").collect().sortBy(_.getString(0))

      val filterFunc: Row => Boolean = row => {
        val x2bar = row.getInt(7)
        val mean = x2barmean
          .filter(keyMean => keyMean.getString(0).equalsIgnoreCase(row.getString(16)))
          .head
          .getDouble(1)
        x2bar <= mean
      }

      val x2barGMean = data.filter(r => !filterFunc(r))
      val x2barLEMean = data.filter(filterFunc)
      val timer = new Timer()
      timer
        .initTimer("transfer")
        .initTimer("src")
      doExperiment(x2barLEMean, x2barGMean, x2barGMean, treeType = STRUT(), maxDepth = 10, numTrees = 50, timer = timer)
      doExperiment(x2barGMean, x2barLEMean, x2barLEMean, treeType = STRUT(), maxDepth = 10, numTrees = 50, timer = timer)
    }

    def testStrutLetter(): Unit = {
      val data = spark.read
        .option("header", "true")
        .option("inferSchema", true)
        .csv("src/main/resources/letter/letter-recognition.csv")

      val x2barmean = data.groupBy("class").agg("x2bar" -> "mean").collect().sortBy(_.getString(0))

      val filterFunc: Row => Boolean = row => {
        val x2bar = row.getInt(7)
        val mean = x2barmean
          .filter(keyMean => keyMean.getString(0).equalsIgnoreCase(row.getString(16)))
          .head
          .getDouble(1)
        x2bar <= mean
      }

      val x2barGMean = data.filter(r => !filterFunc(r))
      val x2barLEMean = data.filter(filterFunc)

      doCrossValidateExperiment(
        x2barLEMean,
        x2barGMean,
        expName = "LetterSTRUT-x2bar<=mean-x2bar>mean",
        treeType = STRUT(),
        numTrees = 1
      )
      doCrossValidateExperiment(
        x2barGMean,
        x2barLEMean,
        expName = "LetterSTRUT-x2bar>mean-x2bar<=mean",
        treeType = STRUT(),
        numTrees = 1
      )
    }

    def testStrutDigits(): Unit = {
      val d6 = spark.read
        .option("header", "true")
        .option("inferSchema", value = true)
        .csv("src/main/resources/digits/optdigits_6.csv")

      val d9 = spark.read
        .option("header", "true")
        .option("inferSchema", value = true)
        .csv("src/main/resources/digits/optdigits_9.csv")

      doCrossValidateExperiment(d9, d6, treeType = STRUT(), maxDepth = 10, numTrees = 50)
    }

    def testStrutSimple(): Unit = {
      val a = spark.read
        .option("header", "true")
        .option("inferSchema", value = true)
        .csv("src/main/resources/simple/load.csv")
        .drop("id")

      val b = spark.read
        .option("header", "true")
        .option("inferSchema", value = true)
        .csv("src/main/resources/simple/load.csv")
        .drop("id")

//      spark.sparkContext.setLogLevel("INFO")
      val timer = new Timer()
      timer
        .initTimer("transfer")
        .initTimer("src")
      doExperiment(a, b, a, treeType = STRUT(), maxDepth = 5, numTrees = 1, timer = timer)
    }

//    testStrutSimple()
//    testStrutDigits()
//    testStrutLetter()
    testBug()
  }

  def printInfo(sourceData: DataFrame, targetData: DataFrame, testData: DataFrame): Unit = {
    println(
      s"Source data.count:${sourceData.count()}\n " +
        s"Target data.count:${targetData.count()}\n " +
        s"Test data.count:${testData.count()}"
    )
  }

  def main(args: Array[String]): Unit = {
//    testNumeric()
    testStrut()
//    testLetter()
//    testWine()
//    testDigits()
//    testLandMine()
//    testMushroom()
    //    pipeline()
    //    testDT()
    //    testLoadToDT("/home/novemser/Documents/Code/DSSM/src/main/resources/mushroom/mushroom.csv")
    //    testMyTree("/home/novemser/Documents/Code/DSSM/src/main/resources/mushroom/mushroom.csv")
    //    testMyTree("/home/novemser/Documents/Code/DSSM/src/main/resources/simple/load.csv")
  }
}
