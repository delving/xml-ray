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

import java.util.concurrent.TimeUnit

import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import dataset.DatasetActor._
import dataset.DsInfo
import dataset.DsInfo._
import mapping.CategoryDb._
import mapping.SkosMappingStore.SkosMapping
import mapping.SkosVocabulary._
import mapping.VocabInfo
import mapping.VocabInfo._
import org.OrgActor
import org.OrgActor.DatasetMessage
import org.OrgContext.{orgContext, ts}
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc._
import services.ProgressReporter.ProgressType._
import services.Temporal._
import triplestore.GraphProperties._
import web.MainController.OkFile

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object AppController extends Controller with Security {

  implicit val timeout = Timeout(500, TimeUnit.MILLISECONDS)

  def listDatasets = SecureAsync() { session => request =>
    listDsInfo(ts).map(list => Ok(Json.toJson(list)))
  }

  def listPrefixes = Secure() { session => request =>
    val prefixes = orgContext.sipFactory.prefixRepos.map(_.prefix)
    Ok(Json.toJson(prefixes))
  }

  def datasetInfo(spec: String) = SecureAsync() { session => request =>
    DsInfo.check(spec, ts).map(info => Ok(Json.toJson(info)))
  }

  def createDataset(spec: String, character: String, mapToPrefix: String) = Secure() { session => request =>
    orgContext.createDatasetRepo(session.actor, spec, character, mapToPrefix)
    Ok(Json.obj("created" -> s"Dataset $spec with character $character and mapToPrefix $mapToPrefix"))
  }

  def datasetProgress(spec: String) = SecureAsync() { session => request =>
    val replyData = (OrgActor.actor ? DatasetMessage(spec, CheckState, question = true)).mapTo[DatasetActorData]
    replyData.map {
      case Dormant =>
        Ok(Json.obj(
          "progressType" -> TYPE_IDLE.name
        ))
      case Active(_, progressState, progressType, count) =>
        Ok(Json.obj(
          "progressState" -> progressState.name,
          "progressType" -> progressType.name,
          "count" -> count
        ))
      case InError(message) =>
        Ok(Json.obj(
          "progressType" -> TYPE_IDLE.name,
          "errorMessage" -> message
        ))
    } recover {
      case t: AskTimeoutException =>
        Ok(Json.obj(
          "progressType" -> TYPE_IDLE.name,
          "errorMessage" -> "actor didn't answer"
        ))
    }
  }

  def command(spec: String, command: String) = SecureAsync() { session => request =>
    if (command == "interrupt") {
      OrgActor.actor ! DatasetMessage(spec, InterruptWork)
      Future(Ok("interrupt sent"))
    }
    else {
      val replyString = (OrgActor.actor ? DatasetMessage(spec, Command(command), question = true)).mapTo[String]
      replyString.map { reply =>
        Ok(Json.obj("reply" -> reply))
      } recover {
        case t: AskTimeoutException =>
          Ok(Json.obj("reply" -> "there was no reply"))
      }
    }
  }

  def uploadDataset(spec: String) = Secure(parse.multipartFormData) { session => request =>
    orgContext.datasetContextOption(spec).map { datasetContext =>
      request.body.file("file").map { file =>
        val error = datasetContext.acceptUpload(file.filename, { target =>
          file.ref.moveTo(target, replace = true)
          Logger.info(s"Dropped file ${file.filename} on $spec: ${target.getAbsolutePath}")
          target
        })
        error.map {
          message => NotAcceptable(Json.obj("problem" -> message))
        } getOrElse {
          Ok
        }
      } getOrElse {
        NotAcceptable(Json.obj("problem" -> "Cannot find file in upload"))
      }
    } getOrElse {
      NotAcceptable(Json.obj("problem" -> s"Cannot find dataset $spec"))
    }
  }

  def setDatasetProperties(spec: String) = SecureAsync(parse.json) { session => request =>
    DsInfo.check(spec, ts).flatMap { dsInfoOpt =>
      dsInfoOpt.map { dsInfo =>
        val propertyList = (request.body \ "propertyList").as[List[String]]
        Logger.info(s"setDatasetProperties $propertyList")
        val diProps: List[NXProp] = propertyList.map(name => allProps.getOrElse(name, throw new RuntimeException(s"Property not recognized: $name")))
        val propsValueOpts = diProps.map(prop => (prop, (request.body \ "values" \ prop.name).asOpt[String]))
        val propsValues = propsValueOpts.filter(t => t._2.isDefined).map(t => (t._1, t._2.get)) // find a better way
        dsInfo.setSingularLiteralProps(propsValues: _*).map(model => Ok)
      } getOrElse {
        Future(NotFound(Json.obj("problem" -> s"dataset $spec not found")))
      }
    }
  }

  def setSkosField(spec: String) = SecureAsync(parse.json) { session => request =>
    val skosFieldUri = (request.body \ "skosFieldUri").as[String]
    val included = (request.body \ "included").as[Boolean]
    DsInfo.check(spec, ts).flatMap { dsInfoOpt =>
      dsInfoOpt.map { dsInfo =>
        Logger.info(s"set skos field $skosFieldUri")
        val currentSkosFields = dsInfo.getUriPropValueList(skosField)
        val (futureOpt, action) = if (included) {
          if (currentSkosFields.contains(skosFieldUri)) {
            (None, "already exists")
          }
          else {
            // rapid skosification could be done here
            (Some(dsInfo.addUriProp(skosField, skosFieldUri)), "added")
          }
        }
        else {
          if (!currentSkosFields.contains(skosFieldUri)) {
            (None, "did not exists")
          }
          else {
            // here eventual de-skosification could happen
            (Some(dsInfo.removeUriProp(skosField, skosFieldUri)), "removed")
          }
        }
        val future = futureOpt.getOrElse(Future(None))
        future.map(ok => Ok(Json.obj("action" -> action)))
      } getOrElse {
        Future(NotFound(Json.obj("problem" -> s"dataset $spec not found")))
      }
    }
  }

  def index(spec: String) = Secure() { session => request =>
    OkFile(orgContext.datasetContext(spec).index)
  }

  def nodeStatus(spec: String, path: String) = Secure() { session => request =>
    orgContext.datasetContext(spec).status(path) match {
      case None => NotFound(Json.obj("path" -> path))
      case Some(file) => OkFile(file)
    }
  }

  def sample(spec: String, path: String, size: Int) = Secure() { session => request =>
    orgContext.datasetContext(spec).sample(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def histogram(spec: String, path: String, size: Int) = Secure() { session => request =>
    orgContext.datasetContext(spec).histogram(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def setRecordDelimiter(spec: String) = Secure(parse.json) { session => request =>
    var recordRoot = (request.body \ "recordRoot").as[String]
    var uniqueId = (request.body \ "uniqueId").as[String]
    orgContext.datasetContextOption(spec).map { datasetContext =>
      datasetContext.setRawDelimiters(recordRoot, uniqueId)
      Ok
    } getOrElse {
      NotFound(Json.obj("problem" -> "Dataset not found"))
    }
  }

  def listVocabularies = SecureAsync() { session => request =>
    listVocabInfo(ts).map(list => Ok(Json.toJson(list)))
  }

  def createVocabulary(spec: String) = SecureAsync() { session => request =>
    VocabInfo.create(session.actor, spec, ts).map(ok =>
      Ok(Json.obj("created" -> s"Skos $spec created"))
    )
  }

  def deleteVocabulary(spec: String) = SecureAsync() { session => request =>
    VocabInfo.check(spec, ts).map { vocabInfoOpt =>
      vocabInfoOpt.map { vocabInfo =>
        vocabInfo.dropVocabulary
        Ok(Json.obj("created" -> s"Vocabulary $spec deleted"))
      } getOrElse {
        NotAcceptable(Json.obj("problem" -> s"Cannot find vocabulary $spec to delete"))
      }
    }
  }

  def uploadVocabulary(spec: String) = SecureAsync(parse.multipartFormData) { session => request =>
    withVocabInfo(spec) { vocabInfo =>
      request.body.file("file").map { bodyFile =>
        val file = bodyFile.ref.file
        ts.dataPutXMLFile(vocabInfo.dataUri, file).map { ok =>
          val now: String = timeToString(new DateTime())
          vocabInfo.setSingularLiteralProps(skosUploadTime -> now)
          Ok
        }
      } getOrElse {
        Future(NotAcceptable(Json.obj("problem" -> "Cannot find file in upload")))
      }
    }
  }

  def vocabularyInfo(spec: String) = Secure() { session => request =>
    withVocabInfo(spec)(vocabInfo => Ok(Json.toJson(vocabInfo)))
  }

  def vocabularyStatistics(spec: String) = SecureAsync() { session => request =>
    withVocabInfo(spec) { vocabInfo =>
      vocabInfo.getStatistics.map(stats => Ok(Json.toJson(stats)))
    }
  }

  def setVocabularyProperties(spec: String) = SecureAsync(parse.json) { session => request =>
    withVocabInfo(spec) { vocabInfo =>
      val propertyList = (request.body \ "propertyList").as[List[String]]
      Logger.info(s"setVocabularyProperties $propertyList")
      val diProps: List[NXProp] = propertyList.map(name => allProps.getOrElse(name, throw new RuntimeException(s"Property not recognized: $name")))
      val propsValueOpts = diProps.map(prop => (prop, (request.body \ "values" \ prop.name).asOpt[String]))
      val propsValues = propsValueOpts.filter(t => t._2.isDefined).map(t => (t._1, t._2.get)) // find a better way
      vocabInfo.setSingularLiteralProps(propsValues: _*).map(model => Ok)
    }
  }

  def searchVocabulary(spec: String, sought: String) = Secure() { session => request =>
    withVocabInfo(spec) { vocabInfo =>
      val v = vocabInfo.vocabulary
      val labelSearch: LabelSearch = v.search(LANGUAGE, sought, 25)
      Ok(Json.obj("search" -> labelSearch))
    }
  }

  def getSkosMappings(specA: String, specB: String) = SecureAsync() { session => request =>
    val store = orgContext.vocabMappingStore(specA, specB)
    store.getMappings.map(tuples => Ok(Json.toJson(tuples.map(t => List(t._1, t._2)))))
  }

  def toggleSkosMapping(specA: String, specB: String) = SecureAsync(parse.json) { session => request =>
    val uriA = (request.body \ "uriA").as[String]
    val uriB = (request.body \ "uriB").as[String]
    val store = orgContext.vocabMappingStore(specA, specB)
    store.toggleMapping(SkosMapping(session.actor, uriA, uriB)).map { action =>
      Ok(Json.obj("action" -> action))
    }
  }

  def getTermVocabulary(spec: String) = SecureAsync() { session => request =>
    DsInfo.check(spec, ts).map { dsInfoOpt =>
      dsInfoOpt.map { dsInfo =>
        val results = dsInfo.vocabulary.concepts.map(concept => {
          //          val freq: Int = concept.frequency.getOrElse(0)
          Json.obj(
            "uri" -> concept.resource.toString,
            "label" -> concept.getAltLabel(LANGUAGE).text,
            "frequency" -> concept.frequency
          )
        })
        Ok(Json.toJson(results))
      } getOrElse {
        NotFound(Json.obj("problem" -> s"No term vocabulary found for $spec"))
      }
    }
  }

  def getTermMappings(dsSpec: String) = SecureAsync() { session => request =>
    val store = orgContext.termMappingStore(dsSpec)
    store.getMappings.map(tuples => Ok(Json.toJson(tuples.map(t => List(t._1, t._2)))))
  }

  def toggleTermMapping(dsSpec: String, vocabSpec: String) = SecureAsync(parse.json) { session => request =>
    val uriA = (request.body \ "uriA").as[String]
    val uriB = (request.body \ "uriB").as[String]
    val store = orgContext.termMappingStore(dsSpec)
    // todo: shouldn't await here, really
    Await.result(VocabInfo.check(vocabSpec, ts), 20.seconds).map { vocabGraph =>
      store.toggleMapping(SkosMapping(session.actor, uriA, uriB), vocabGraph).map { action =>
        Ok(Json.obj("action" -> action))
      }
    } getOrElse {
      Future(NotFound(Json.obj("problem" -> s"No vocabulary found for $vocabSpec")))
    }
  }

  def listSipFiles(spec: String) = Secure() { session => request =>
    val datasetContext = orgContext.datasetContext(spec)
    val fileNames = datasetContext.sipRepo.listSips.map(_.file.getName)
    Ok(Json.obj("list" -> fileNames))
  }

  def deleteLatestSipFile(spec: String) = Secure() { session => request =>
    val datasetContext = orgContext.datasetContext(spec)
    val sips = datasetContext.sipRepo.listSips
    if (sips.size < 2) {
      NotFound(Json.obj("problem" -> s"Refusing to delete the last SIP file $spec"))
    }
    else {
      FileUtils.deleteQuietly(sips.head.file)
      Ok(Json.obj("deleted" -> sips.head.file.getName))
    }
  }

  // todo: things under here unfinished

  def getCategoryList = Secure() { session => request =>
    orgContext.categoriesRepo.categoryListOption.map { list =>
      Ok(Json.toJson(list))
    } getOrElse {
      Ok(Json.obj("message" -> "No category file"))
    }
  }

  def gatherCategoryCounts = Secure() { session => request =>
    orgContext.startCategoryCounts()
    Ok
  }

  def getCategorySourcePaths(spec: String) = Secure() { session => request =>
    val datasetContext = orgContext.datasetContext(spec)
    val sourcePaths = datasetContext.categoryDb.getSourcePaths
    Ok(Json.obj("sourcePaths" -> sourcePaths))
  }

  def getCategoryMappings(spec: String) = Secure() { session => request =>
    val datasetContext = orgContext.datasetContext(spec)
    val mappings: Seq[CategoryMapping] = datasetContext.categoryDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setCategoryMapping(spec: String) = Secure(parse.json) { session => request =>
    val datasetContext = orgContext.datasetContext(spec)
    val categoryMapping = CategoryMapping(
      (request.body \ "source").as[String],
      Seq((request.body \ "category").as[String])
    )
    val member = (request.body \ "member").as[Boolean]
    datasetContext.categoryDb.setMapping(categoryMapping, member)
    Ok("Mapping " + (if (member) "added" else "removed"))
  }

  def listSheets = Secure() { session => request =>
    Ok(Json.obj("sheets" -> orgContext.categoriesRepo.listSheets))
  }

  def sheet(spec: String) = Action(parse.anyContent) { implicit request =>
    OkFile(orgContext.categoriesRepo.sheet(spec))
  }

}
