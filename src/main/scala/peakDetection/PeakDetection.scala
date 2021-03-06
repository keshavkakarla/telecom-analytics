/*

Copyright 2015-2016 FORTH

Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.

*/
package ta

import scala.util.{Try, Success, Failure}

import java.text.SimpleDateFormat

import com.typesafe.config.{Config, ConfigValueFactory}
import org.apache.spark._
import org.apache.spark.SparkContext._

import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import org.joda.time.DateTime

import scala.util.Try

class PeakDetection extends Serializable{
  val appName = this.getClass().getSimpleName
  val usage = s"Usage: submit.sh ${appName} <master> <cpBaseFile> <testDataFile> <binSize>"

  private def calcEvents(cpBaseStr: RDD[String], cpAnalyzeStr: RDD[String]) = {
    val cpBase = cpBaseStr.map(CpBase(_))
    val cpAnalyze = cpAnalyzeStr.map(DataRaw(_))

    val am = cpAnalyze.map( dr => ((dr.dow, dr.doy), dr.num) )
      .aggregateByKey(0)(_ + _, _ + _)
      .map { case ((dow, doy), sum) => (dow, doy, sum) }

    val bm = cpBase.map(cb => (cb.dow, cb.num))
      .aggregateByKey(0)(_ + _, _ + _)

    val cpAnalyzeJoinAm = cpAnalyze.map(dr => (dr.doy, dr))
      .join(am.map{ case (dow, doy, num) => (doy, num) })
      .map{ case (doy, (dr, sum)) =>
      (Key(dr.id, dr.hour, dr.dow), (doy, dr.num.toDouble/sum, dr.num))}

    val cpBaseJoinBm = cpBase.map(cp =>
      (cp.dow, cp)
    ).join(bm).map { case (dow, (cp, sum)) =>
      (Key(cp.id, cp.hour, cp.dow), (cp.num.toDouble/sum, cp.num))}

    cpAnalyzeJoinAm.join(cpBaseJoinBm).map {
      case (k, ((doy, amNum, aNum), (bmNum, bNum))) =>
        Event(k.id, k.hour, doy, k.dow, amNum/bmNum - 1, aNum, bNum)
    }
  }

  def calcEventsFilter(events: RDD[Event], binSize: Double) = {
    events.filter{case e@Event(_,_,_,_,_,_,_) =>
      Math.abs(e.ratio) >= 0.2 && Math.abs(e.aNum - e.bNum) >= 50 && e.ratio > 0
    }.map{ case e@ Event(_,_,_,_,_,_,_) =>
      (e.id, e.hour, e.doy, e.dow, Math.floor(Math.abs(e.ratio / binSize)) * binSize * Math.signum(e.ratio))
    }
  }

  def run(cpBase: RDD[String], testData: RDD[String], binSize: Double, output: String) = {
    val events = calcEvents(cpBase, testData)
    events.persist(StorageLevel.MEMORY_ONLY_SER)
    events.saveAsTextFile(s"${output}/events")

    val eventsFilter = calcEventsFilter(events, binSize)
    eventsFilter.persist(StorageLevel.MEMORY_ONLY_SER)
    eventsFilter.saveAsTextFile(s"${output}/eventsFilter")
    eventsFilter
  }
}

object PeakDetection extends PeakDetection {
  def main(args: Array[String]) {
      val appName = this.getClass().getSimpleName

      args.toList match {
        case master :: cpBaseFile :: testDataFile :: output :: binSize :: Nil =>
          val conf = new SparkConf()
            .setAppName(appName)
            .setMaster(master)
          val sc = new SparkContext(conf)

          val cpBase = sc.textFile(cpBaseFile)
          val testData = sc.textFile(testDataFile)

          val eventsFilter = run(cpBase, testData, binSize.toDouble, output)
          println(s"Found ${eventsFilter.count} events after filtering.")
        case _ =>
          val usage = (s"Usage: submit.sh ${appName} <master> <cpBaseFile> <testDataFile> <output> <binSize (Int)>")
          System.err.println(usage)
          System.exit(1)
    }
  }
}
