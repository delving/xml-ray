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

package org

import java.io.File

import dataset.DatasetRepo
import harvest.Harvesting.{Harvest, PMHResumptionToken, PublishedDataset, RepoMetadataFormat}
import org.OrgRepo._
import org.joda.time.DateTime
import play.api.Play.current
import play.api.cache.Cache
import record.RecordHandling
import record.RecordHandling.StoredRecord
import services._

import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps
import scala.xml.Elem

object OrgRepo {
  lazy val repo = new OrgRepo(NarthexConfig.USER_HOME, NarthexConfig.ORG_ID)

  val SUFFIXES = List(".xml.gz", ".xml")

  def pathToDirectory(path: String) = path.replace(":", "_").replace("@", "_")

  def acceptableFile(fileName: String, contentType: Option[String]) = {
    // todo: be very careful about files matching a regex, so that they have spec__prefix form
    // todo: something with content-type
    println("content type " + contentType)
    SUFFIXES.find(suffix => fileName.endsWith(suffix))
  }

  def getSuffix(fileName: String) = {
    val suffix = SUFFIXES.filter(suf => fileName.endsWith(suf))
    if (suffix.isEmpty) "" else suffix.head
  }

  def stripSuffix(fileName: String) = {
    val suffix = getSuffix(fileName)
    fileName.substring(0, fileName.length - suffix.length)
  }

  def isPublishedOaiPmh(elem: Elem): Boolean = (elem \ "publication" \ "oaipmh").text == "true"

  case class SipZip
  (
    zipFile: File,
    uploadedBy: String,
    uploadedOn: String,
    factsFile: File,
    facts: Map[String, String],
    hintsFile: File,
    hints: Map[String, String]
    ) {
    override def toString = zipFile.getName
  }


}

class OrgRepo(userHome: String, val orgId: String) extends RecordHandling {
  val root = new File(userHome, "NarthexFiles")
  val orgRoot = new File(root, orgId)
  val datasetsDir = new File(orgRoot, "dastasets")
  val sipZipDir = new File(orgRoot, "sip-zip")
  val repoDb = new RepoDb(orgId)

  orgRoot.mkdirs()
  datasetsDir.mkdir()
  sipZipDir.mkdir()

  private def listFiles(directory: File): List[File] = {
    if (!directory.exists()) return List.empty
    directory.listFiles.filter(f => f.isFile && SUFFIXES.filter(end => f.getName.endsWith(end)).nonEmpty).toList
  }

  def datasetRepo(fileName: String): DatasetRepo = {
    val dr = new DatasetRepo(this, stripSuffix(fileName))
    dr.mkdirs
    dr
  }

  def datasetRepoOption(fileName: String): Option[DatasetRepo] = {
    val dr = datasetRepo(fileName)
    if (dr.datasetDb.infoOption.isDefined) Some(dr) else None
  }

  def getPublishedDatasets: Seq[PublishedDataset] = {
    val FileName = "(.*)__(.*)".r
    BaseX.withSession {
      session =>
        repoDb.listDatasets.flatMap {
          dataset =>
            val fr = datasetRepo(dataset.name)
            val FileName(spec, prefix) = dataset.name
            val publishedOaiPmh = isPublishedOaiPmh(dataset.info)
            if (publishedOaiPmh) {
              val namespaces = (dataset.info \ "namespaces" \ "_").map(node => (node.label, node.text))
              val metadataFormat = namespaces.find(_._1 == prefix) match {
                case Some(ns) => RepoMetadataFormat(prefix, ns._2)
                case None => RepoMetadataFormat(prefix)
              }
              Some(PublishedDataset(
                spec = dataset.name,
                prefix = prefix,
                name = (dataset.info \ "metadata" \ "name").text,
                description = (dataset.info \ "metadata" \ "description").text,
                dataProvider = (dataset.info \ "metadata" \ "dataProvider").text,
                totalRecords = (dataset.info \ "delimit" \ "recordCount").text.toInt,
                metadataFormat = metadataFormat
              ))
            }
            else {
              None
            }
        }
    }
  }

  def getMetadataFormats: Seq[RepoMetadataFormat] = {
    getPublishedDatasets.sortBy(_.metadataFormat.namespace).map(d => (d.prefix, d.metadataFormat)).toMap.values.toSeq
  }

  def getHarvest(resumptionToken: PMHResumptionToken, enriched: Boolean): (List[StoredRecord], Option[PMHResumptionToken]) = {
    Cache.getAs[Harvest](resumptionToken.value).map { harvest =>
      val pageSize = NarthexConfig.OAI_PMH_PAGE_SIZE
      val start = 1 + (harvest.currentPage - 1) * pageSize
      val repo = datasetRepo(harvest.repoName)
      val storedRecords = repo.recordDb.recordHarvest(harvest.from, harvest.until, start, pageSize)
      val records = if (enriched) repo.enrichRecords(storedRecords) else repo.parseStoredRecords(storedRecords)
      harvest.next.map { next =>
        Cache.set(next.resumptionToken.value, next, 2 minutes)
        (records, Some(next.resumptionToken))
      } getOrElse {
        (records, None)
      }
    } getOrElse {
      throw new RuntimeException(s"Resumption token not found: $resumptionToken")
    }
  }

  // === sip-zip

  def createSipZipFile(fileName: String) = new File(sipZipDir, fileName)

  def createSipZipFactsFile(fileName: String) = new File(sipZipDir, s"$fileName.facts")

  def createSipZipHintsFile(fileName: String) = new File(sipZipDir, s"$fileName.hints")

  val SipZipName = "sip_(.+)__(\\d+)_(\\d+)_(\\d+)_(\\d+)_(\\d+)__(.*).zip".r

  def readMapFile(factsFile: File): Map[String, String] = {
    if (!factsFile.exists()) return Map.empty
    val lines = Source.fromFile(factsFile).getLines()
    lines.flatMap {
      line =>
        val equals = line.indexOf("=")
        if (equals < 0) None
        else {
          Some((line.substring(0, equals).trim, line.substring(equals + 1).trim))
        }
    }.toMap
  }

  def listSipZips: Seq[SipZip] = {
    if (!sipZipDir.exists()) return Seq.empty
    val fileList = sipZipDir.listFiles.filter(file => file.isFile && file.getName.endsWith(".zip")).toList
    val ordered = fileList.sortBy {
      f =>
        val n = f.getName
        val parts = n.split("__")
        if (parts.length >= 2) parts(1) else parts(0)
    }
    ordered.reverse.map {
      file =>
        val factsFile = createSipZipFactsFile(file.getName)
        val facts = readMapFile(factsFile)
        val hintsFile = createSipZipHintsFile(file.getName)
        val hints = readMapFile(hintsFile)
        val SipZipName(spec, year, month, day, hour, minute, uploadedBy) = file.getName
        val dateTime = new DateTime(year.toInt, month.toInt, day.toInt, hour.toInt, minute.toInt)
        SipZip(
          zipFile = file,
          uploadedBy = uploadedBy,
          uploadedOn = XSD_FORMATTER.print(dateTime),
          factsFile = factsFile,
          facts = facts,
          hintsFile = hintsFile,
          hints = hints
        )
    }
  }
}