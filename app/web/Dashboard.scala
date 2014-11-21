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

package web

import dataset.DatasetOrigin._
import dataset.DatasetState._
import dataset.SipRepo._
import dataset._
import harvest.Harvesting
import harvest.Harvesting.HarvestType
import harvest.Harvesting.HarvestType._
import mapping.CategoryDb._
import mapping.SkosVocabulary
import mapping.SkosVocabulary._
import mapping.TermDb._
import org.OrgActor
import org.OrgActor.DatasetMessage
import org.OrgRepo.repo
import org.apache.commons.io.FileUtils
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json._
import play.api.mvc._
import record.Saver.GenerateSource
import web.Application.OkFile

object Dashboard extends Controller with Security {

  val SOURCE_SUFFIXES = List(".xml.gz", ".xml")

  val DATASET_PROPERTY_LISTS = List(
    "origin",
    "metadata",
    "status",
    "progress",
    "tree",
    "records",
    "publication",
    "categories",
    "progress",
    "delimit",
    "namespaces",
    "harvest",
    "harvestCron",
    "sipFacts",
    "sipHints"
  )

  def list = Secure() { token => implicit request =>
    val datasets = repo.repoDb.listDatasets.flatMap { dataset =>
      val state = DatasetState.datasetStateFromInfo(dataset.info)
      val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(dataset.info, name))
      Some(Json.obj("name" -> dataset.datasetName, "info" -> JsObject(lists)))
    }
    Ok(JsArray(datasets))
  }

  def datasetInfo(datasetName: String) = Secure() { token => implicit request =>
    repo.datasetRepo(datasetName).datasetDb.infoOpt.map { info =>
      val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(info, name))
      Ok(JsObject(lists))
    } getOrElse NotFound(Json.obj("problem" -> s"Not found $datasetName"))
  }

  def revert(datasetName: String, command: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val change = command match {
      case "interrupt" =>
        val interrupted = datasetRepo.interruptProgress
        s"Interrupted: $interrupted"
      case "tree" =>
        datasetRepo.clearTreeDir()
        datasetRepo.datasetDb.setTree(ready = false)
        "Tree removed"
      case "records" =>
        datasetRepo.recordDbOpt.map(_.dropDb())
        datasetRepo.datasetDb.setRecords(ready = false)
        "Records removed"
      case "new" =>
        datasetRepo.datasetDb.createDataset
        "New dataset created"
      case _ =>
        val revertedStateOpt = datasetRepo.revertState
        s"Reverted to $revertedStateOpt"
    }
    Ok(Json.obj("change" -> change))
  }

  def upload(datasetName: String, prefix: String) = Secure(parse.multipartFormData) { token => implicit request =>
    repo.datasetRepoOption(datasetName).map { datasetRepo =>
      request.body.file("file").map { file =>
        val fileName = file.filename
        Logger.info(s"Dropped file $fileName onto $datasetName")
        val isSourceFile = SOURCE_SUFFIXES.exists(suffix => fileName.endsWith(suffix))
        if (fileName.endsWith(".xml.gz") || fileName.endsWith(".xml")) {
          datasetRepo.datasetDb.setOrigin(DROP, prefix)
          datasetRepo.datasetDb.setStatus(RAW)
          file.ref.moveTo(datasetRepo.createRawFile(fileName))
          datasetRepo.startAnalysis()
          Ok(datasetRepo.datasetName)
        }
        else if (fileName.endsWith(".sip.zip")) {
          val sipZipFile = datasetRepo.sipRepo.createSipZipFile(fileName)
          file.ref.moveTo(sipZipFile, replace = true)
          val originOpt: Option[DatasetOrigin] = datasetRepo.sipRepo.latestSipFile.flatMap { sipFile =>
            sipFile.sipMappingOpt.map { sipMapping =>
              (sipFile.harvestUrl, sipFile.harvestSpec, sipFile.harvestPrefix) match {
                case (Some(harvestUrl), Some(harvestSpec), Some(harvestPrefix)) =>
                  datasetRepo.datasetDb.setOrigin(SIP_HARVEST, sipMapping.prefix)
                  datasetRepo.datasetDb.setHarvestInfo(PMH, harvestUrl, harvestSpec, harvestPrefix)
                  datasetRepo.datasetDb.setRecordDelimiter(PMH.recordRoot, PMH.uniqueId, sipFile.recordCount.map(_.toInt).getOrElse(0))
                  SIP_HARVEST
                case _ =>
                  datasetRepo.datasetDb.setOrigin(SIP_SOURCE, sipMapping.prefix)
                  val recordCount = sipFile.recordCount.map(_.toInt).getOrElse(0)
                  datasetRepo.datasetDb.setRecordDelimiter(SIP_SOURCE_RECORD_ROOT, SIP_SOURCE_UNIQUE_ID, recordCount)
                  datasetRepo.datasetDb.setStatus(DatasetState.SOURCED)
                  Logger.info(s"Triggering generate source from zip: $datasetName")
                  OrgActor.actor ! DatasetMessage(datasetName, GenerateSource())
                  // todo: trigger saving?
                  SIP_SOURCE
              }
            }
            // todo: sipFacts & sipHints
          }
          originOpt.map { origin =>
            Ok(origin.toString)
          } getOrElse {
            NotAcceptable(s"Unable to determine origin for $datasetName")
          }
        }
        else {
          NotAcceptable(Json.obj("problem" -> s"Unrecognized file suffix: $fileName"))
        }
      } getOrElse {
        NotAcceptable(Json.obj("problem" -> s"Cannot find file in the uploaded data $datasetName"))
      }
    } getOrElse {
      NotAcceptable(Json.obj("problem" -> s"Cannot find dataset $datasetName"))
    }
  }

  def harvest(datasetName: String) = Secure(parse.json) { token => implicit request =>
    def optional(tag: String) = (request.body \ tag).asOpt[String] getOrElse ""
    def required(tag: String) = (request.body \ tag).asOpt[String] getOrElse (throw new IllegalArgumentException(s"Missing $tag"))
    try {
      val datasetRepo = repo.datasetRepo(datasetName)
      Logger.info(s"harvest ${required("url")} (${optional("dataset")}) to $datasetName")
      HarvestType.harvestTypeFromString(required("harvestType")) map { harvestType =>
        val prefix = if (harvestType == HarvestType.PMH) required("prefix") else HarvestType.ADLIB.name
        datasetRepo.firstHarvest(harvestType, required("url"), optional("dataset"), prefix)
        Ok
      } getOrElse {
        NotAcceptable(Json.obj("problem" -> s"unknown harvest type: ${optional("harvestType")}"))
      }
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setHarvestCron(datasetName: String) = Secure(parse.json) { token => implicit request =>
    def required(tag: String) = (request.body \ tag).asOpt[String] getOrElse (throw new IllegalArgumentException(s"Missing $tag"))
    try {
      val datasetRepo = repo.datasetRepo(datasetName)
      val cron = Harvesting.harvestCron(required("previous"), required("delay"), required("unit"))
      Logger.info(s"harvest $cron")
      datasetRepo.datasetDb.setHarvestCron(cron)
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setMetadata(datasetName: String) = Secure(parse.json) { token => implicit request =>
    try {
      val obj = request.body.as[JsObject]
      val meta: Map[String, String] = obj.value.map(nv => nv._1 -> nv._2.as[String]).toMap
      Logger.info(s"saveMetadata: $meta")
      val datasetRepo = repo.datasetRepo(datasetName)
      datasetRepo.datasetDb.setMetadata(meta)
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setPublication(datasetName: String) = Secure(parse.json) { token => implicit request =>
    def boolParam(tag: String) = (request.body \ tag).asOpt[String] getOrElse "false"
    def stringParam(tag: String) = (request.body \ tag).asOpt[String] getOrElse ""
    try {
      val datasetRepo = repo.datasetRepo(datasetName)
      datasetRepo.datasetDb.setPublication(boolParam("oaipmh"), boolParam("index"), boolParam("lod"))
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setCategories(datasetName: String) = Secure(parse.json) { token => implicit request =>
    def param(tag: String) = (request.body \ tag).asOpt[String] getOrElse "false"
    try {
      val datasetRepo = repo.datasetRepo(datasetName)
      datasetRepo.datasetDb.setCategories(param("included"))
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def analyze(datasetName: String) = Secure() { token => implicit request =>
    repo.datasetRepoOption(datasetName) match {
      case Some(datasetRepo) =>
        datasetRepo.startAnalysis()
        Ok
      case None =>
        NotFound(Json.obj("problem" -> s"Not found $datasetName"))
    }
  }

  def index(datasetName: String) = Secure() { token => implicit request =>
    OkFile(repo.datasetRepo(datasetName).index)
  }

  def nodeStatus(datasetName: String, path: String) = Secure() { token => implicit request =>
    repo.datasetRepo(datasetName).status(path) match {
      case None => NotFound(Json.obj("path" -> path))
      case Some(file) => OkFile(file)
    }
  }

  def sample(datasetName: String, path: String, size: Int) = Secure() { token => implicit request =>
    repo.datasetRepo(datasetName).sample(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def histogram(datasetName: String, path: String, size: Int) = Secure() { token => implicit request =>
    repo.datasetRepo(datasetName).histogram(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def setRecordDelimiter(datsetName: String) = Secure(parse.json) { token => implicit request =>
    var recordRoot = (request.body \ "recordRoot").as[String]
    var uniqueId = (request.body \ "uniqueId").as[String]
    var recordCount = (request.body \ "recordCount").as[Int]
    val datasetRepo = repo.datasetRepo(datsetName)
    datasetRepo.datasetDb.setRecordDelimiter(recordRoot, uniqueId, recordCount)
    println(s"store recordRoot=$recordRoot uniqueId=$uniqueId recordCount=$recordCount")
    Ok
  }

  def saveRecords(datasetName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    datasetRepo.firstSaveRecords()
    Ok
  }

  def queryRecords(datasetName: String) = Secure(parse.json) { token => implicit request =>
    val path = (request.body \ "path").as[String]
    val value = (request.body \ "value").as[String]
    val datasetRepo = repo.datasetRepo(datasetName)
    datasetRepo.recordDbOpt.map { recordDb =>
      val recordsString = recordDb.recordsWithValue(path, value)
      val enrichedRecords = datasetRepo.enrichRecords(recordsString)
      val result = enrichedRecords.map(rec => rec.text).mkString("\n")
      Ok(result)
    } getOrElse {
      NotFound(Json.obj("problem" -> "No record database found"))
    }
  }

  def listSheets = Secure() { token => implicit request =>
    Ok(Json.obj("sheets" -> repo.categoriesRepo.listSheets))
  }

  def sheet(datasetName: String) = Action(parse.anyContent) { implicit request =>
    OkFile(repo.categoriesRepo.sheet(datasetName))
  }

  def listSkos = Secure() { token => implicit request =>
    Ok(Json.obj("list" -> repo.skosRepo.listFiles))
  }

  def searchSkos(name: String, sought: String) = Secure() { token => implicit request =>
    def searchVocabulary(vocabulary: SkosVocabulary): LabelSearch = vocabulary.search("dut", sought, 25)
    Cache.getAs[SkosVocabulary](name) map {
      vocabulary => Ok(Json.obj("search" -> searchVocabulary(vocabulary)))
    } getOrElse {
      val vocabulary = repo.skosRepo.vocabulary(name)
      Cache.set(name, vocabulary, CACHE_EXPIRATION)
      Ok(Json.obj("search" -> searchVocabulary(vocabulary)))
    }
  }

  def getTermSourcePaths(datasetName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val sourcePaths = datasetRepo.termDb.getSourcePaths
    Ok(Json.obj("sourcePaths" -> sourcePaths))
  }

  def getTermMappings(datasetName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val mappings: scala.Seq[TermMapping] = datasetRepo.termDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setTermMapping(datasetName: String) = Secure(parse.json) { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    datasetRepo.invalidateEnrichmentCache()
    if ((request.body \ "remove").asOpt[String].isDefined) {
      val sourceUri = (request.body \ "source").as[String]
      datasetRepo.termDb.removeMapping(sourceUri)
      Ok("Mapping removed")
    }
    else {
      val sourceUri = (request.body \ "source").as[String]
      val targetUri = (request.body \ "target").as[String]
      val vocabulary = (request.body \ "vocabulary").as[String]
      val prefLabel = (request.body \ "prefLabel").as[String]
      datasetRepo.termDb.addMapping(TermMapping(sourceUri, targetUri, vocabulary, prefLabel))
      Ok("Mapping added")
    }
  }

  def getCategoryList = Secure() { token => implicit request =>
    repo.categoriesRepo.categoryListOption.map { list =>
      Ok(Json.toJson(list))
    } getOrElse {
      Ok(Json.obj("message" -> "No category file"))
    }
  }

  def gatherCategoryCounts = Secure() { token => implicit request =>
    repo.startCategoryCounts()
    Ok
  }

  def getCategorySourcePaths(datasetName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val sourcePaths = datasetRepo.categoryDb.getSourcePaths
    Ok(Json.obj("sourcePaths" -> sourcePaths))
  }

  def getCategoryMappings(datasetName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val mappings: Seq[CategoryMapping] = datasetRepo.categoryDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setCategoryMapping(datasetName: String) = Secure(parse.json) { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val categoryMapping = CategoryMapping(
      (request.body \ "source").as[String],
      Seq((request.body \ "category").as[String])
    )
    val member = (request.body \ "member").as[Boolean]
    datasetRepo.categoryDb.setMapping(categoryMapping, member)
    Ok("Mapping " + (if (member) "added" else "removed"))
  }

  def listSipFiles(datasetName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val fileNames = datasetRepo.sipRepo.listSipFiles.map(_.file.toString)
    Ok(Json.obj("list" -> fileNames))
  }

  def deleteLatestSipFile(datasetName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val sipFiles = datasetRepo.sipRepo.listSipFiles
    if (sipFiles.size < 2) {
      NotFound(Json.obj("problem" -> s"Refusing to delete the last SIP file $datasetName"))
    }
    else {
      FileUtils.deleteQuietly(sipFiles.head.file)
      Ok(Json.obj("deleted" -> sipFiles.head.file.getName))
    }
  }
}
