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

package mapping

import java.util.UUID

import org.ActorStore.NXActor
import triplestore.Sparql._
import triplestore.{SkosGraph, TripleStore}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object SkosMappingStore {

  case class SkosMapping(actor: NXActor, uriA: String, uriB: String) {

    val uri = s"${actor.uri}/mapping/${UUID.randomUUID().toString}"

    val existenceQ = doesMappingExistQ(uriA, uriB)

    val deleteQ = deleteMappingQ(uriA, uriB)

    def insertQ(skosA: SkosGraph, skosB: SkosGraph) = insertMappingQ(actor, uri, uriA, uriB, skosA, skosB)

    override def toString = uri
  }

}

class VocabMappingStore(skosA: SkosGraph, skosB: SkosGraph, ts: TripleStore) {

  import mapping.SkosMappingStore._

  def toggleMapping(mapping: SkosMapping): Future[String] = {
    ts.ask(mapping.existenceQ).flatMap { exists =>
      if (exists) {
        ts.up.sparqlUpdate(mapping.deleteQ).map(ok => "removed")
      }
      else {
        ts.up.sparqlUpdate(mapping.insertQ(skosA, skosB)).map(ok => "added")
      }
    }
  }

  def getMappings: Future[Seq[(String, String)]] = {
    ts.query(getVocabMappingsQ(skosA, skosB)).map(_.map(ab => (ab("a").text, ab("b").text)))
  }

}


class TermMappingStore(termGraph: SkosGraph, ts: TripleStore) {

  import mapping.SkosMappingStore._

  def toggleMapping(mapping: SkosMapping, vocabGraph: SkosGraph): Future[String] = {
    ts.ask(mapping.existenceQ).flatMap { exists =>
      if (exists) {
        ts.up.sparqlUpdate(mapping.deleteQ).map(ok => "removed")
      }
      else {
        ts.up.sparqlUpdate(mapping.insertQ(termGraph, vocabGraph)).map(ok => "added")
      }
    }
  }

  def getMappings: Future[List[List[String]]] = {
    ts.query(getTermMappingsQ(termGraph)).map { resultMap =>
      resultMap.map { ab =>
        List(ab("termUri").text, ab("vocabUri").text, ab("vocabSpec").text)
      }
    }
  }
}

