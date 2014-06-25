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

package services

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}

trait SkosHandling {

  case class ConceptScheme(about: String) {
    val concepts = mutable.MutableList[Concept]()
    val topConcepts = mutable.MutableList[Concept]()

    override def toString: String =
      s"""
         |ConceptScheme($about):
         |Concepts:
         |${concepts.mkString("")}
         |TopConcepts:
         |${topConcepts.mkString("")}
       """.stripMargin
  }

  case class Concept(about: String) {
    val prefLabels = mutable.MutableList[PrefLabel]()
    val altLabels = mutable.MutableList[AltLabel]()
    val definitions = mutable.MutableList[Definition]()
    val narrowerConcepts = mutable.MutableList[Concept]()
    val broaderConcepts = mutable.MutableList[Concept]()
    val narrowerResources = mutable.MutableList[String]()
    val broaderResources = mutable.MutableList[String]()

    def narrower(other: Concept) = {
      narrowerConcepts.find(_.about == other.about) match {
        case None => narrowerConcepts += other
        case _ =>
      }
      other.broaderConcepts.find(_.about == about) match {
        case None => other.broaderConcepts += this
        case _ =>
      }
    }

    def resolve(concepts: mutable.HashMap[String, Concept]) = {
      narrowerResources.foreach {
        uri =>
          concepts.get(uri) match {
            case Some(concept) => this.narrower(concept)
            case None => throw new RuntimeException(s"Cannot find concept for $uri")
          }
      }
      narrowerResources.clear()
      broaderResources.foreach {
        uri =>
          concepts.get(uri) match {
            case Some(concept) => concept.narrower(this)
            case None => throw new RuntimeException(s"Cannot find concept for $uri")
          }
      }
      broaderResources.clear()
    }

    override def toString: String =
      s"""
         |Concept($about)
         |  PrefLabels: ${prefLabels.mkString(",")}
         |   AltLabels: ${altLabels.mkString(",")}
         | Definitions: ${definitions.mkString(",")}
         |    Narrower: ${narrowerConcepts.map(_.prefLabels.head).mkString(",")}
         |     Broader: ${broaderConcepts.map(_.prefLabels.head).mkString(",")}
       """.stripMargin
  }

  case class Definition(language: String) {
    var text: String = ""

    override def toString: String = s"""Definition[$language]("$text")"""
  }

  case class PrefLabel(language: String) {
    var text: String = ""

    override def toString: String = s"""PrefLabel[$language]("$text")"""
  }

  case class AltLabel(language: String) {
    var text: String = ""

    override def toString: String = s"""AltLabel[$language]("$text")"""
  }

  object SkosVocabulary {

    val XML = "http://www.w3.org/XML/1998/namespace"
    val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    val SKOS = "http://www.w3.org/2004/02/skos/core#"


    def apply(source: Source) = {

      val events = new XMLEventReader(source)

      def attribute(uri: String, label: String, attributes: MetaData, scope: NamespaceBinding) = {
        attributes.get(uri, scope, label) match {
          case None => None
          case Some(x) => Some(x.head.text)
        }
      }

      def about(attributes: MetaData, scope: NamespaceBinding) = attribute(RDF, "about", attributes, scope)
      def lang(attributes: MetaData, scope: NamespaceBinding) = attribute(XML, "lang", attributes, scope)
      def resource(attributes: MetaData, scope: NamespaceBinding) = attribute(RDF, "resource", attributes, scope)

      var activeConceptScheme: Option[ConceptScheme] = None
      var activeConcept: Option[Concept] = None
      var activePrefLabel: Option[PrefLabel] = None
      var activeAltLabel: Option[AltLabel] = None
      var activeDefinition: Option[Definition] = None
      var concepts = new mutable.HashMap[String, Concept]()
      val textBuilder = new mutable.StringBuilder()

      while (events.hasNext) {
        events.next() match {

          // ===============================
          case EvElemStart("rdf", "RDF", attributes, scope) =>
            println("starting RDF")
          case EvElemEnd("rdf", "RDF") =>
            textBuilder.clear()
            println("finished RDF")

          // ===============================
          case EvElemStart("skos", "ConceptScheme", attributes, scope) =>
            activeConceptScheme = Some(new ConceptScheme(about(attributes, scope).get))
          case EvElemEnd("skos", "ConceptScheme") =>
            textBuilder.clear()

          // ===============================
          case EvElemStart("skos", "topConceptOf", attributes, scope) =>
            val r = resource(attributes, scope)
            activeConceptScheme.map {
              s =>
                if (s.about != r.get) throw new RuntimeException
                s.topConcepts += activeConcept.get
            }
          case EvElemEnd("skos", "topConceptOf") =>
            textBuilder.clear()

          // ===============================
          case EvElemStart("skos", "inScheme", attributes, scope) =>
            val r = resource(attributes, scope)
            activeConceptScheme.map(s => if (s.about != r.get) throw new RuntimeException)
          case EvElemEnd("skos", "inScheme") =>
            textBuilder.clear()


          // ===============================
          case EvElemStart("skos", "Concept", attributes, scope) =>
            val concept: Concept = new Concept(about(attributes, scope).get)
            activeConcept = Some(concept)
            concepts.put(concept.about, concept)
            activeConceptScheme.map(_.concepts += concept)
          case EvElemEnd("skos", "Concept") =>
            textBuilder.clear()

          // ===============================
          case EvElemStart("skos", "definition", attributes, scope) =>
            activeConcept.map(c => {
              val definition: Definition = new Definition(lang(attributes, scope).get)
              activeDefinition = Some(definition)
              c.definitions += definition
            })
          case EvElemEnd("skos", "definition") =>
            activeDefinition.map(d => d.text = textBuilder.toString())
            textBuilder.clear()

          // ===============================
          case EvElemStart("skos", "prefLabel", attributes, scope) =>
            activeConcept.map(c => {
              val prefLabel = new PrefLabel(lang(attributes, scope).get)
              activePrefLabel = Some(prefLabel)
              c.prefLabels += prefLabel
            })
          case EvElemEnd("skos", "prefLabel") =>
            activePrefLabel.map(d => d.text = textBuilder.toString())
            textBuilder.clear()

          // ===============================
          case EvElemStart("skos", "altLabel", attributes, scope) =>
            activeConcept.map(c => {
              val altLabel: AltLabel = new AltLabel(lang(attributes, scope).get)
              activeAltLabel = Some(altLabel)
              c.altLabels += altLabel
            })
          case EvElemEnd("skos", "altLabel") =>
            activeAltLabel.map(d => d.text = textBuilder.toString())
            textBuilder.clear()

          // ===============================
          case EvElemStart("skos", "broader", attributes, scope) =>
            activeConcept.map(c => c.broaderResources += resource(attributes, scope).get)
          case EvElemEnd("skos", "broader") =>
            textBuilder.clear()

          // ===============================
          case EvElemStart("skos", "narrower", attributes, scope) =>
            activeConcept.map(c => c.narrowerResources += resource(attributes, scope).get)
          case EvElemEnd("skos", "narrower") =>
            textBuilder.clear()

          // ===============================
          case EvElemStart(pre, label, attributes, scope) =>
            println(s"!!!START $label")
            attributes.foreach {
              attr =>
                println(s"Attribute $attr")
            }
          case EvElemEnd(pre, label) =>
            println(s"!!!END $label")

          // ===============================
          case EvText(text) =>
            val crunched = FileHandling.crunchWhitespace(text)
            if (!crunched.isEmpty) {
              textBuilder.append(crunched)
              //              println(s"Text '$crunched'")
            }

          case EvEntityRef(entity) =>
          //            println(s"Entity $entity")

          case EvComment(text) =>
          //            println(s"Comment $text")
          //            FileHandling.stupidParser(text, string => node.value(FileHandling.translateEntity(string)))

          case x =>
            println("EVENT? " + x)
        }
      }
      if (activeConceptScheme.isEmpty) throw new RuntimeException("No concept scheme")
      activeConceptScheme.get.concepts.foreach(_.resolve(concepts))
      activeConceptScheme.get
    }
  }

}
