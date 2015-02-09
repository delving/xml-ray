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

package triplestore

import dataset.DsInfo._
import org.ActorStore.{NXActor, NXProfile}
import org.joda.time.DateTime
import services.StringHandling._
import services.Temporal
import triplestore.GraphProperties._
import triplestore.TripleStore.QueryValue

trait Sparql {
  // === actor store ===

  def insertActorQ(actor: NXActor, passwordHashString: String, adminActor: NXActor) =
    s"""
      |WITH <$actorsGraph>
      |DELETE {
      |   <$actor>
      |      a <$actorEntity>;
      |      <$username> ?userName ;
      |      <$actorOwner> ?userMaker ;
      |      <$passwordHash> ?passwordHash .
      |}
      |INSERT {
      |   <$actor>
      |      a <$actorEntity>;
      |      <$username> '''${actor.actorName}''' ;
      |      <$actorOwner> <$adminActor> ;
      |      <$passwordHash> '''$passwordHashString''' .
      |}
      |WHERE {
      |   OPTIONAL {
      |      <$actor>
      |         a <$actorEntity>;
      |         <$username> ?username ;
      |         <$actorOwner> ?userMaker ;
      |         <$passwordHash> ?passwordHash .
      |   }
      |}
     """.stripMargin

  def setActorProfileQ(actor: NXActor, profile: NXProfile) =
    s"""
      |WITH <$actorsGraph>
      |DELETE {
      |   <$actor>
      |      <$userFirstName> ?firstName ;
      |      <$userLastName> ?lastName ;
      |      <$userEMail> ?email .
      |}
      |INSERT {
      |   <$actor>
      |      <$userFirstName> '''${profile.firstName}''' ;
      |      <$userLastName> '''${profile.lastName}''' ;
      |      <$userEMail> '''${profile.email}''' .
      |}
      |WHERE {
      |   <$actor>
      |      a <$actorEntity>;
      |      <$userFirstName> ?firstName ;
      |      <$userLastName> ?lastName ;
      |      <$userEMail> ?email .
      |}
     """.stripMargin

  def setActorPasswordQ(actor: NXActor, passwordHashString:String) =
    s"""
      |WITH <$actorsGraph>
      |DELETE { <$actor> <$passwordHash> ?oldPassword }
      |INSERT { <$actor> <$passwordHash> "$passwordHashString" }
      |WHERE { <$actor> <$passwordHash> ?oldPassword }
     """.stripMargin

  // === dataset info ===

  val selectDatasetSpecsQ =
    s"""
      |SELECT ?spec
      |WHERE {
      |  GRAPH ?g {
      |    ?s <$datasetSpec> ?spec .
      |    FILTER NOT EXISTS { ?s <$deleted> true }
      |  }
      |}
      |ORDER BY ?spec
     """.stripMargin

  def askIfDatasetExistsQ(uri: String) =
    s"""
      |ASK {
      |   GRAPH <$uri> { <$uri> <$datasetSpec> ?spec }
      |}
     """.stripMargin

  def updatePropertyQ(uri: String, prop: NXProp, value: String) =
    s"""
      |WITH <$uri>
      |DELETE { <$uri> <$prop> ?o }
      |INSERT { <$uri> <$prop> '''$value''' }
      |WHERE {
      |   OPTIONAL { <$uri> <$prop> ?o }
      |}
     """.stripMargin.trim

  def removeLiteralPropertyQ(uri: String, prop: NXProp) =
    s"""
      |WITH <$uri>
      |DELETE { <$uri> <$prop> ?o }
      |WHERE { <$uri> <$prop> ?o }
     """.stripMargin

  def addUriPropertyQ(uri: String, prop: NXProp, uriValue: String) =
    s"""
      |INSERT DATA {
      |   GRAPH <$uri> { <$uri> <$prop> '''$uriValue''' }
      |}
     """.stripMargin.trim

  def deleteUriPropertyQ(uri: String, prop: NXProp, uriValue: String) =
    s"""
      |WITH <$uri>
      |DELETE { <$uri> <$prop> '''$uriValue''' }
      |WHERE { <$uri> <$prop> '''$uriValue''' }
     """.stripMargin

  def deleteDatasetQ(uri: String, skosUri: String) =
    s"""
      |WITH <$uri>
      |INSERT { <$uri> <$deleted> true }
      |WHERE { ?s ?p ?o };
      |WITH <$skosUri>
      |DELETE { ?s ?p ?o }
      |WHERE { ?s ?p ?o };
      |DELETE {
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |      ?record <$belongsTo> <$uri>
      |   }
      |}
      |WHERE {
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |      ?record <$belongsTo> <$uri>
      |   }
      |};
     """.stripMargin

  // === vocab info ===

  val listVocabInfoQ =
    s"""
      |SELECT ?spec
      |WHERE {
      |  GRAPH ?g { ?s <$skosSpec> ?spec }
      |}
      |ORDER BY ?spec
     """.stripMargin

  def checkVocabQ(skosUri: String) =
    s"""
      |ASK {
      |   GRAPH ?g { <$skosUri> <$skosSpec> ?spec }
      |}
     """.stripMargin

  def getVocabStatisticsQ(uri: String) =
    s"""
      |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |SELECT (count(?s) as ?count)
      |WHERE {
      |  GRAPH <$uri> { ?s rdf:type skos:Concept }
      |}
     """.stripMargin

  def dropVocabularyQ(uri: String) =
    s"""
      |WITH <$uri>
      |DELETE { ?s ?p ?o }
      |WHERE { ?s ?p ?o }
     """.stripMargin

  // === mapping store ===

  def doesMappingExistQ(uriA: String, uriB: String) =
    s"""
      |ASK {
      |  GRAPH ?g {
      |    ?mapping
      |       a <$mappingEntity>;
      |       <$mappingConcept> <$uriA>;
      |       <$mappingConcept> <$uriB> .
      |  }
      |}
     """.stripMargin

  def deleteMappingQ(uriA: String, uriB: String) =
    s"""
      |DELETE {
      |  GRAPH ?g {
      |    ?mapping
      |      ?p ?o;
      |      <$mappingConcept> <$uriA> ;
      |      <$mappingConcept> <$uriB> .
      |  }
      |}
      |INSERT {
      |  GRAPH ?g { ?mapping <$mappingDeleted> true }
      |}
      |WHERE {
      |  GRAPH ?g {
      |    ?mapping
      |      ?p ?o ;
      |      <$mappingConcept> <$uriA> ;
      |      <$mappingConcept> <$uriB> .
      |  }
      |}
     """.stripMargin

  def insertMappingQ(actor: NXActor, uri: String, uriA: String, uriB: String, skosA: SkosGraph, skosB: SkosGraph) =
    s"""
      |PREFIX skos: <$SKOS>
      |INSERT DATA {
      |  GRAPH <$uri> {
      |    <$uriA> skos:exactMatch <$uriB> .
      |    <$uri>
      |       a <$mappingEntity>;
      |       <$synced> false;
      |       <$belongsTo> <$actor> ;
      |       <$mappingTime> '''${Temporal.timeToString(new DateTime)}''' ;
      |       <$mappingConcept> <$uriA> ;
      |       <$mappingConcept> <$uriB> ;
      |       <$mappingVocabulary> <${skosA.uri}> ;
      |       <$mappingVocabulary> <${skosB.uri}> .
      |  }
      |}
     """.stripMargin

  def getVocabMappingsQ(skosA: SkosGraph, skosB: SkosGraph) =
    s"""
      |PREFIX skos: <$SKOS>
      |SELECT ?a ?b
      |WHERE {
      |  GRAPH ?g {
      |    ?a skos:exactMatch ?b .
      |    ?s <$mappingVocabulary> <${skosA.uri}> .
      |    ?s <$mappingVocabulary> <${skosB.uri}> .
      |  }
      |}
     """.stripMargin

  def getTermMappingsQ(terms: SkosGraph) =
    s"""
      |PREFIX skos: <$SKOS>
      |SELECT ?a ?b
      |WHERE {
      |  GRAPH ?g {
      |    ?a skos:exactMatch ?b .
      |    ?s <$mappingVocabulary> <${terms.uri}> .
      |  }
      |}
     """.stripMargin

  // === skosification ===

  val listSkosifiedFieldsQ =
    s"""
      |SELECT ?datasetUri ?fieldProperty
      |WHERE {
      |  GRAPH ?g { ?datasetUri <$skosField> ?fieldProperty }
      |}
     """.stripMargin

  object SkosifiedField {
    def apply(resultMap: Map[String, QueryValue]): SkosifiedField = {
      SkosifiedField(resultMap("datasetUri").text, resultMap("fieldProperty").text)
    }
  }

  case class SkosifiedField(datasetUri: String, fieldPropertyUri: String)

  def skosificationCasesExist(sf: SkosifiedField) = 
    s"""
      |ASK {
      |  GRAPH ?g { ?record <$belongsTo> <${sf.datasetUri}> }
      |  GRAPH ?g {
      |    ?anything <${sf.fieldPropertyUri}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
     """.stripMargin

  def listSkosificationCasesQ(sf: SkosifiedField, chunkSize: Int) = 
    s"""
      |SELECT DISTINCT ?literalValue
      |WHERE {
      |  GRAPH ?g { ?record <$belongsTo> <${sf.datasetUri}> }
      |  GRAPH ?g {
      |    ?anything <${sf.fieldPropertyUri}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
      |LIMIT $chunkSize
     """.stripMargin

  def createCases(sf: SkosifiedField, resultList: List[Map[String, QueryValue]]): List[SkosificationCase] =
    resultList.map(_("literalValue")).map(v => SkosificationCase(sf, v))

  case class SkosificationCase(sf: SkosifiedField, literalValue: QueryValue) {
    val mintedUri = s"${sf.datasetUri}/${urlEncodeValue(literalValue.text)}"
    val fieldProperty = sf.fieldPropertyUri
    val skosGraph = getSkosUri(sf.datasetUri)
    val datasetUri = sf.datasetUri
    val value = literalValue.text

    val ensureSkosEntryQ =
      s"""
        |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
        |INSERT {
        |   GRAPH <$skosGraph> {
        |      <$mintedUri> a skos:Concept .
        |      <$mintedUri> skos:altLabel '''$value''' .
        |      <$mintedUri> <$belongsTo> <$datasetUri> .
        |      <$mintedUri> <$synced> false .
        |   }
        |}
        |WHERE {
        |   FILTER NOT EXISTS {
        |      GRAPH <$skosGraph> {
        |         ?existing a skos:Concept
        |         FILTER( ?existing = <$mintedUri> )
        |      }
        |   }
        |};
       """.stripMargin

    val literalToUriQ =
      s"""
        |DELETE {
        |  GRAPH ?g { ?s <$fieldProperty> '''$value''' }
        |}
        |INSERT {
        |  GRAPH ?g { ?s <$fieldProperty> <$mintedUri> }
        |}
        |WHERE {
        |  GRAPH ?g { ?record <$belongsTo> <$datasetUri> }
        |  GRAPH ?g { ?s <$fieldProperty> '''$value''' }
        |};
       """.stripMargin
  }
}
