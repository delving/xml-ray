//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package actors

import java.io._

import actors.Merger._
import actors.Sorter._
import akka.actor.{Actor, Props}
import services.NodeRepo

import scala.language.postfixOps

object Sorter {

  case class SortType(ordering: Ordering[String])

  object SortType {
    val VALUE_SORT = SortType(Ordering[String])
    val HISTOGRAM_SORT: SortType = SortType(Ordering[String].reverse)
  }

  case class Sort(sortType: SortType)

  case class Sorted(nodeRepo: NodeRepo, sortedFile: File, sortType: SortType)

  def props(nodeRepo: NodeRepo) = Props(new Sorter(nodeRepo))
}

class Sorter(val nodeRepo: NodeRepo) extends Actor {
  val linesToSort = 10000
  var sortFiles = List.empty[File]
  var merges = List.empty[Merge]

  def initiateMerges(outFile: File, sortType: SortType): Unit = {
    while (sortFiles.size > 1) {
      sortFiles = sortFiles match {
        case inFileA :: inFileB :: remainder =>
          val merge: Merge = Merge(inFileA, inFileB, outFile, sortType)
          merges = merge :: merges
          val merger = context.actorOf(Merger.props(nodeRepo))
          merger ! merge
          remainder
        case tooSmall =>
          tooSmall

      }
    }
  }

  def reportSorted(sortedFile: File, sortType: SortType): Unit = {
    sortFiles.head.renameTo(sortedFile)
    sortFiles = List.empty
    context.parent ! Sorted(nodeRepo, sortedFile, sortType)
  }

  def receive = {

    case Sort(sortType) =>
      val (inputFile, sortedFile) = sortType match {
        case SortType.VALUE_SORT => (nodeRepo.values, nodeRepo.sorted)
        case SortType.HISTOGRAM_SORT => (nodeRepo.counted, nodeRepo.histogramText)
      }
      val input = new BufferedReader(new FileReader(inputFile))

      var lines = List.empty[String]
      def dumpSortedToFile() = {
        val outputFile = nodeRepo.tempSort
        val output = new FileWriter(outputFile)
        lines.sorted(sortType.ordering).foreach {
          line =>
            output.write(line)
            output.write("\n")
        }
        output.close()
        lines = List.empty[String]
        sortFiles = outputFile :: sortFiles
      }

      var count = linesToSort
      while (count > 0) {
        val line = input.readLine()
        if (line == null) {
          if (lines.nonEmpty) dumpSortedToFile()
          count = 0
        }
        else {
          lines = line :: lines
          count -= 1
          if (count == 0) {
            dumpSortedToFile()
            count = linesToSort
          }
        }
      }
      input.close()
      initiateMerges(sortedFile, sortType)
      if (merges.isEmpty) reportSorted(sortedFile, sortType)

    case Merged(merge, file, sortType) =>
      merges = merges.filter(pending => pending != merge)
      sortFiles = file :: sortFiles
      if (merges.isEmpty) {
        initiateMerges(merge.mergeResultFile, sortType)
        if (merges.isEmpty) reportSorted(merge.mergeResultFile, sortType)
      }
  }
}




