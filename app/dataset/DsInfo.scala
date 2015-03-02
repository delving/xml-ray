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
import mapping.{SkosVocabulary, TermMappingStore, VocabInfo}
import org.ActorStore.NXActor
import org.OrgActor.DatasetMessage
import org.OrgContext.NX_URI_PREFIX
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json.{JsValue, Json, Writes}
import services.StringHandling.urlEncodeValue
import services.Temporal._
import triplestore.GraphProperties._
import triplestore.Sparql._
import triplestore.{SkosGraph, TripleStore}

import scala.collection.JavaConversions._
import scala.concurrent._
import scala.concurrent.duration._

object DsInfo {

  val patience = 1.minute

  val cacheTime = 10.minutes

  case class DsCharacter(name: String)

  val CharacterMapped = DsCharacter("character-mapped")

  def getCharacter(characterString: String) = List(CharacterMapped).find(_.name == characterString)

  case class DsState(prop: NXProp) {
    override def toString = prop.name
  }

  object DsState {
    val RAW = DsState(stateRaw)
    val RAW_ANALYZED = DsState(stateRawAnalyzed)
    val SOURCED = DsState(stateSourced)
    val MAPPABLE = DsState(stateMappable)
    val PROCESSABLE = DsState(stateProcessable)
    val PROCESSED = DsState(stateProcessed)
    val ANALYZED = DsState(stateAnalyzed)
    val SAVED = DsState(stateSaved)
  }

  case class DsMetadata(name: String,
                        description: String,
                        aggregator: String,
                        owner: String,
                        language: String,
                        rights: String)

  implicit val dsInfoWrites = new Writes[DsInfo] {
    def writes(dsInfo: DsInfo): JsValue = {
      val out = new StringWriter()
      RDFDataMgr.write(out, dsInfo.model, RDFFormat.JSONLD_FLAT)
      Json.parse(out.toString)
    }
  }

  def listDsInfo(implicit ec: ExecutionContext, ts: TripleStore): Future[List[DsInfo]] = {
    ts.query(selectDatasetSpecsQ).map { list =>
      list.map { entry =>
        val spec = entry("spec").text
        new DsInfo(spec)
      }
    }
  }

  def getDsInfoUri(spec: String) = s"$NX_URI_PREFIX/dataset/${urlEncodeValue(spec)}"

  def getDsSkosUri(datasetUri: String) = s"$datasetUri/skos"

  def createDsInfo(owner: NXActor, spec: String, character: DsCharacter, mapToPrefix: String)(implicit ec: ExecutionContext, ts: TripleStore): Future[DsInfo] = {
    val m = ModelFactory.createDefaultModel()
    val uri = m.getResource(getDsInfoUri(spec))
    m.add(uri, m.getProperty(rdfType), m.getResource(datasetEntity))
    m.add(uri, m.getProperty(datasetSpec.uri), m.createLiteral(spec))
    m.add(uri, m.getProperty(datasetCharacter.uri), m.createLiteral(character.name))
    m.add(uri, m.getProperty(actorOwner.uri), m.createResource(owner.uri))
    val trueLiteral = m.createLiteral("true")
    m.add(uri, m.getProperty(publishOAIPMH.uri), trueLiteral)
    m.add(uri, m.getProperty(publishIndex.uri), trueLiteral)
    m.add(uri, m.getProperty(publishLOD.uri), trueLiteral)
    m.add(uri, m.getProperty(acceptanceOnly.uri), trueLiteral)
    if (mapToPrefix != "-") m.add(uri, m.getProperty(datasetMapToPrefix.uri), m.createLiteral(mapToPrefix))
    ts.up.dataPost(uri.getURI, m).map { ok =>
      val cacheName = getDsInfoUri(spec)
      val dsInfo = new DsInfo(spec)
      Cache.set(cacheName, dsInfo, cacheTime)
      dsInfo
    }
  }

  def freshDsInfo(spec: String)(implicit ec: ExecutionContext, ts: TripleStore): Future[Option[DsInfo]] = {
    ts.ask(askIfDatasetExistsQ(getDsInfoUri(spec))).map(answer =>
      if (answer) Some(new DsInfo(spec)) else None
    )
  }

  def withDsInfo[T](spec: String)(block: DsInfo => T)(implicit ec: ExecutionContext, ts: TripleStore) = {
    val cacheName = getDsInfoUri(spec)
    Cache.getAs[DsInfo](cacheName) map { dsInfo =>
      block(dsInfo)
    } getOrElse {
      val dsInfo = Await.result(freshDsInfo(spec), 30.seconds).getOrElse {
        throw new RuntimeException(s"No dataset info for $spec")
      }
      Cache.set(cacheName, dsInfo, cacheTime)
      block(dsInfo)
    }
  }

}

class DsInfo(val spec: String)(implicit ec: ExecutionContext, ts: TripleStore) extends SkosGraph {

  import dataset.DsInfo._

  def now: String = timeToString(new DateTime())

  val uri = getDsInfoUri(spec)

  val skosified = true

  val skosUri = getDsSkosUri(uri)

  // could cache as well so that the get happens less
  def futureModel = ts.dataGet(uri)
  futureModel.onFailure {
    case e: Throwable => Logger.warn(s"No data found for dataset $spec", e)
  }

  def model = Await.result(futureModel, patience)

  def getLiteralProp(prop: NXProp): Option[String] = {
    val m = model
    val res = m.getResource(uri)
    val objects = m.listObjectsOfProperty(res, m.getProperty(prop.uri))
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def getTimeProp(prop: NXProp): Option[DateTime] = getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: NXProp) = getLiteralProp(prop).exists(_ == "true")

  def setSingularLiteralProps(propVals: (NXProp, String)*): Unit = {
    val sparqlPerPropQ = propVals.map(pv => updatePropertyQ(uri, pv._1, pv._2)).toList
    val withSynced = updateSyncedFalseQ(uri) :: sparqlPerPropQ
    val sparql = withSynced.mkString(";\n")
    val futureUpdate = ts.up.acceptanceOnly(getBooleanProp(acceptanceOnly)).sparqlUpdate(sparql)
    Await.ready(futureUpdate, patience)
  }

  def removeLiteralProp(prop: NXProp): Unit = {
    val futureUpdate = ts.up.acceptanceOnly(getBooleanProp(acceptanceOnly)).sparqlUpdate(removeLiteralPropertyQ(uri, prop))
    Await.ready(futureUpdate, patience)
  }

  def getUriPropValueList(prop: NXProp): List[String] = {
    val m = model
    val res = m.getResource(uri)
    m.listObjectsOfProperty(res, m.getProperty(prop.uri)).map(node =>
      node.asLiteral().toString
    ).toList
  }

  def addUriProp(prop: NXProp, uriValueString: String): Unit = {
    val futureUpdate = ts.up.acceptanceOnly(getBooleanProp(acceptanceOnly)).sparqlUpdate(addUriPropertyQ(uri, prop, uriValueString))
    Await.ready(futureUpdate, patience)
  }

  def removeUriProp(prop: NXProp, uriValueString: String): Unit = {
    val futureUpdate = ts.up.acceptanceOnly(getBooleanProp(acceptanceOnly)).sparqlUpdate(deleteUriPropertyQ(uri, prop, uriValueString))
    Await.ready(futureUpdate, patience)
  }

  def dropDataset = {
    ts.up.sparqlUpdate(deleteDatasetQ(uri, skosUri)).map(ok => true)
  }

  def toggleProduction(): Future[Boolean] = {
    val production = ts.up.production
    if (getBooleanProp(acceptanceOnly)) {
      for {
        datasetModel <- ts.dataGet(uri)
        putDataset <- production.dataPutGraph(uri, datasetModel)
        skosModel <- ts.dataGet(skosUri)
        putSkos <- production.dataPutGraph(skosUri, skosModel)
      } yield {
        setSingularLiteralProps(acceptanceOnly -> "false")
        false
      }
    }
    else {
      setSingularLiteralProps(acceptanceOnly -> "true")
      Future(true)
    }
  }

  def setState(state: DsState) = setSingularLiteralProps(state.prop -> now)

  def removeState(state: DsState) = removeLiteralProp(state.prop)

  def setError(message: String) = {
    if (message.isEmpty) {
      removeLiteralProp(datasetErrorMessage)
    }
    else {
      setSingularLiteralProps(
        datasetErrorMessage -> message,
        datasetErrorTime -> now
      )
    }
  }

  def setRecordCount(count: Int) = setSingularLiteralProps(datasetRecordCount -> count.toString)

  def setProcessedRecordCounts(validCount: Int, invalidCount: Int) = setSingularLiteralProps(
    processedValid -> validCount.toString,
    processedInvalid -> invalidCount.toString
  )

  def setIncrementalProcessedRecordCounts(validCount: Int, invalidCount: Int) = setSingularLiteralProps(
    processedIncrementalInvalid -> validCount.toString,
    processedIncrementalInvalid -> invalidCount.toString
  )

  def setHarvestInfo(harvestTypeEnum: HarvestType, url: String, dataset: String, prefix: String) = setSingularLiteralProps(
    harvestType -> harvestTypeEnum.name,
    harvestURL -> url,
    harvestDataset -> dataset,
    harvestPrefix -> prefix
  )

  def setHarvestCron(harvestCron: HarvestCron = currentHarvestCron) = setSingularLiteralProps(
    harvestPreviousTime -> timeToString(harvestCron.previous),
    harvestDelay -> harvestCron.delay.toString,
    harvestDelayUnit -> harvestCron.unit.toString
  )

  def setMetadata(metadata: DsMetadata) = setSingularLiteralProps(
    datasetName -> metadata.name,
    datasetDescription -> metadata.description,
    datasetAggregator -> metadata.aggregator,
    datasetOwner -> metadata.owner,
    datasetLanguage -> metadata.language,
    datasetRights -> metadata.rights
  )

  def currentHarvestCron = {
    (getLiteralProp(harvestPreviousTime), getLiteralProp(harvestDelay), getLiteralProp(harvestDelayUnit), getLiteralProp(harvestIncremental)) match {
      case (Some(previousString), Some(delayString), Some(unitString), Some(incrementalString)) =>
        HarvestCron(
          previous = stringToTime(previousString),
          delay = delayString.toInt,
          unit = DelayUnit.fromString(unitString).getOrElse(DelayUnit.WEEKS),
          incremental = incrementalString.toBoolean
        )
      case _ =>
        HarvestCron(new DateTime(), 1, DelayUnit.WEEKS, incremental = false)
    }
  }

  def termCategoryMap(categoryVocabularyInfo: VocabInfo): Map[String, List[String]] = {
    val mappingStore = new TermMappingStore(this)
    val mappings = Await.result(mappingStore.getMappings(categories = true), 1.minute)
    val uriLabelMap = categoryVocabularyInfo.vocabulary.uriLabelMap
    val termUriLabels = mappings.flatMap { mapping =>
      val termUri = mapping(0)
      val categoryUri = mapping(1)
      uriLabelMap.get(categoryUri).map(label => (termUri, label))
    }
    termUriLabels.groupBy(_._1).map(group => group._1 -> group._2.map(_._2))
  }

  // for actors

  def createMessage(payload: AnyRef, question: Boolean = false) = DatasetMessage(spec, payload, question)

  def toTurtle = {
    val sw = new StringWriter()
    model.write(sw, "TURTLE")
    sw.toString
  }

  lazy val vocabulary = new SkosVocabulary(spec, skosUri)

  override def toString = spec
}
