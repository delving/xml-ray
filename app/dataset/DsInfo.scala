//===========================================================================
//    Copyright 2015 Delving B.V.
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

package dataset

import java.io.StringWriter

import com.hp.hpl.jena.rdf.model._
import harvest.Harvesting.{HarvestCron, HarvestType}
import org.ActorStore
import org.ActorStore.NXActor
import org.OrgActor.DatasetMessage
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Json, Writes}
import services.NarthexConfig._
import services.StringHandling.urlEncodeValue
import services.Temporal._
import triplestore.TripleStore
import triplestore.TripleStore._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

object DsInfo {

  var allDatasetProps = Map.empty[String, DIProp]

  case class DIProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
    allDatasetProps = allDatasetProps + (name -> this)
  }

  case class Character(name: String)

  val CharacterMapped = Character("character-mapped")
  def getCharacter(characterString: String) = List(CharacterMapped).find(_.name == characterString)
  val datasetCharacter = DIProp("datasetCharacter")

  val datasetSpec = DIProp("datasetSpec")
  val datasetName = DIProp("datasetName")
  val datasetDescription = DIProp("datasetDescription")
  val datasetOwner = DIProp("datasetOwner")
  val datasetLanguage = DIProp("datasetLanguage")
  val datasetRights = DIProp("datasetRights")

  val datasetMapToPrefix = DIProp("datasetMapToPrefix")

  val datasetRecordCount = DIProp("datasetRecordCount", intProp)
  val datasetErrorTime = DIProp("datasetErrorTime")
  val datasetErrorMessage = DIProp("datasetErrorMessage")

  val skosField = DIProp("skosField", uriProp)

  val stateRaw = DIProp("stateRaw", timeProp)
  val stateRawAnalyzed = DIProp("stateRawAnalyzed", timeProp)
  val stateSource = DIProp("stateSource", timeProp)
  val stateMappable = DIProp("stateMappable", timeProp)
  val stateProcessable = DIProp("stateProcessable", timeProp)
  val stateProcessed = DIProp("stateProcessed", timeProp)
  val stateAnalyzed = DIProp("stateAnalyzed", timeProp)
  val stateSaved = DIProp("stateSaved", timeProp)

  val harvestType = DIProp("harvestType")
  val harvestURL = DIProp("harvestURL")
  val harvestDataset = DIProp("harvestDataset")
  val harvestPrefix = DIProp("harvestPrefix")
  val harvestSearch = DIProp("harvestSearch")
  val harvestPreviousTime = DIProp("harvestPreviousTime", timeProp)
  val harvestDelay = DIProp("harvestDelay")
  val harvestDelayUnit = DIProp("harvestDelayUnit")

  val processedValid = DIProp("processedValid", intProp)
  val processedInvalid = DIProp("processedInvalid", intProp)

  val publishOAIPMH = DIProp("publishOAIPMH", booleanProp)
  val publishIndex = DIProp("publishIndex", booleanProp)
  val publishLOD = DIProp("publishLOD", booleanProp)
  val categoriesInclude = DIProp("categoriesInclude", booleanProp)

  case class DsState(prop: DIProp) {
    override def toString = prop.name
  }

  object DsState {
    val RAW = DsState(stateRaw)
    val RAW_ANALYZED = DsState(stateRawAnalyzed)
    val SOURCED = DsState(stateSource)
    val MAPPABLE = DsState(stateMappable)
    val PROCESSABLE = DsState(stateProcessable)
    val PROCESSED = DsState(stateProcessed)
    val ANALYZED = DsState(stateAnalyzed)
    val SAVED = DsState(stateSaved)
  }

  case class DsMetadata(name: String,
                        description: String,
                        owner: String,
                        language: String,
                        rights: String)

  implicit val dsInfoWrites = new Writes[DsInfo] {
    def writes(dsInfo: DsInfo): JsValue = {
      val out = new StringWriter()
      RDFDataMgr.write(out, dsInfo.m, RDFFormat.JSONLD_FLAT)
      Json.parse(out.toString)
    }
  }

  def listDsInfo(ts: TripleStore): Future[List[DsInfo]] = {
    val q =
      s"""
         |SELECT ?spec
         |WHERE {
         |  GRAPH ?g {
         |    ?s <${datasetSpec.uri}> ?spec .
         |  }
         |}
         |ORDER BY ?spec
       """.stripMargin
    ts.query(q).map { list =>
      list.map { entry =>
        val spec = entry("spec").text
        new DsInfo(spec, ts)
      }
    }
  }

  def getDsUri(spec: String) = s"$NX_URI_PREFIX/dataset/${urlEncodeValue(spec)}"

  def create(owner: NXActor, spec: String, character: Character, mapToPrefix:String, ts: TripleStore): Future[DsInfo] = {
    val m = ModelFactory.createDefaultModel()
    val uri = m.getResource(getDsUri(spec))
    m.add(uri, m.getProperty(datasetSpec.uri), m.createLiteral(spec))
    m.add(uri, m.getProperty(datasetCharacter.uri), m.createLiteral(character.name))
    m.add(uri, m.getProperty(ActorStore.actorOwner.uri), m.createResource(owner.uri))
    if (mapToPrefix != "-") m.add(uri, m.getProperty(datasetMapToPrefix.uri), m.createLiteral(mapToPrefix))
    ts.dataPost(uri.getURI, m).map(ok => new DsInfo(spec, ts))
  }

  def check(spec: String, ts: TripleStore): Future[Option[DsInfo]] = {
    val dsUri = getDsUri(spec)
    val q =
      s"""
         |ASK {
         |   GRAPH <$dsUri> {
         |       <$dsUri> <${datasetSpec.uri}> ?spec .
         |   }
         |}
       """.stripMargin
    ts.ask(q).map(answer => if (answer) Some(new DsInfo(spec, ts)) else None)
  }
}

class DsInfo(val spec: String, ts: TripleStore) {

  import dataset.DsInfo._

  def now: String = timeToString(new DateTime())

  val dsUri = getDsUri(spec)

  // could cache as well so that the get happens less
  lazy val futureModel = ts.dataGet(dsUri)
  futureModel.onFailure {
    case e: Throwable => Logger.warn(s"No data found for dataset $spec", e)
  }
  lazy val m: Model = Await.result(futureModel, 20.seconds)
  lazy val uri = m.getResource(dsUri)

  def getLiteralProp(prop: DIProp): Option[String] = {
    val objects = m.listObjectsOfProperty(uri, m.getProperty(prop.uri))
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def getTimeProp(prop: DIProp): Option[DateTime] = getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: DIProp) = getLiteralProp(prop).exists(_ == "true")

  def setSingularLiteralProps(tuples: (DIProp, String)*): Future[Model] = {
    val propVal = tuples.map(t => (m.getProperty(t._1.uri), t._2))
    val sparqlPerProp = propVal.map { pv =>
      val propUri = pv._1
      s"""
         |WITH <$dsUri>
         |DELETE { 
         |   <$dsUri> <$propUri> ?o .
         |}
         |INSERT { 
         |   <$dsUri> <$propUri> "${pv._2}" .
         |}
         |WHERE { 
         |   OPTIONAL {
         |      <$dsUri> <$propUri> ?o .
         |   } 
         |}
       """.stripMargin.trim
    }
    val sparql = sparqlPerProp.mkString(";\n")
    ts.update(sparql).map { ok =>
      propVal.foreach { pv =>
        m.removeAll(uri, pv._1, null)
        m.add(uri, pv._1, m.createLiteral(pv._2))
      }
      m
    }
  }

  def removeLiteralProp(prop: DIProp): Future[Model] = {
    val propUri = m.getProperty(prop.uri)
    val sparql =
      s"""
         |WITH <$dsUri>
         |DELETE {
         |   <$dsUri> <$propUri> ?o .
         |}
         |WHERE {
         |   <$dsUri> <$propUri> ?o .
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      m.removeAll(uri, propUri, null)
      m
    }
  }

  def getUriPropValueList(prop: DIProp): List[String] = {
    val propUri = m.getProperty(prop.uri)
    m.listObjectsOfProperty(uri, propUri).map(node => node.asResource().toString).toList
  }

  def addUriProp(prop: DIProp, uriValue: String): Future[Model] = {
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getResource(uriValue)
    val sparql = s"""
         |INSERT DATA {
         |   GRAPH <$dsUri> {
         |      <$dsUri> <$propUri> <$uriValueUri> .
         |   }
         |}
       """.stripMargin.trim
    ts.update(sparql).map { ok =>
      m.add(uri, propUri, uriValueUri)
      m
    }
  }

  def removeUriProp(prop: DIProp, uriValue: String): Future[Model] = futureModel.flatMap { m =>
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getProperty(uriValue)
    val sparql =
      s"""
         |DELETE DATA FROM <$dsUri> {
         |   <$dsUri> <$propUri> <$uriValueUri> .
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      m.remove(uri, propUri, uriValueUri)
      m
    }
  }

  def dropDataset = {
    val sparql =
      s"""
         |DELETE {
         |   GRAPH <$dsUri> {
         |      <$dsUri> ?p ?o .
         |   }
         |}
         |WHERE {
         |   GRAPH <$dsUri> {
         |      <$dsUri> ?p ?o .
         |   }
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      true
    }
  }

  // from the old datasetdb

  def setState(state: DsState) = setSingularLiteralProps(state.prop -> now)

  def removeState(state: DsState) = removeLiteralProp(state.prop)

  def setError(message: String) = setSingularLiteralProps(
    datasetErrorMessage -> message,
    datasetErrorTime -> now
  )

  def setRecordCount(count: Int) = setSingularLiteralProps(datasetRecordCount -> count.toString)

  def setProcessedRecordCounts(validCount: Int, invalidCount: Int) = setSingularLiteralProps(
    processedValid -> validCount.toString,
    processedInvalid -> invalidCount.toString
  )

  def setHarvestInfo(harvestTypeEnum: HarvestType, url: String, dataset: String, prefix: String) = setSingularLiteralProps(
    harvestType -> harvestTypeEnum.name,
    harvestURL -> url,
    harvestDataset -> dataset,
    harvestPrefix -> prefix
  )

  def setHarvestCron(harvestCron: HarvestCron) = setSingularLiteralProps(
    harvestPreviousTime -> timeToString(harvestCron.previous),
    harvestDelay -> harvestCron.delay.toString,
    harvestDelayUnit -> harvestCron.unit.toString
  )

  def setMetadata(metadata: DsMetadata) = setSingularLiteralProps(
    datasetName -> metadata.name,
    datasetDescription -> metadata.description,
    datasetOwner -> metadata.owner,
    datasetLanguage -> metadata.language,
    datasetRights -> metadata.rights
  )

  def harvestCron = {
    (getLiteralProp(harvestPreviousTime), getLiteralProp(harvestDelay), getLiteralProp(harvestDelayUnit)) match {
      case (Some(previousString), Some(delayString), Some(unitString)) =>
        HarvestCron(
          previous = stringToTime(previousString),
          delay = delayString.toInt,
          unit = DelayUnit.fromString(unitString).getOrElse(DelayUnit.WEEKS)
        )
      case _ =>
        HarvestCron(new DateTime(), 1, DelayUnit.WEEKS)
    }
  }

  // for actors

  def createMessage(payload: AnyRef, question: Boolean = false) = DatasetMessage(spec, payload, question)

  override def toString = spec
}