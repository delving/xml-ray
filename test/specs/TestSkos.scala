package specs

import java.io.File

import dataset.DsInfo
import mapping.SkosMappingStore.SkosMapping
import mapping.SkosVocabulary.LabelSearch
import mapping.{VocabInfo, VocabMappingStore}
import org.ActorStore
import org.apache.commons.io.FileUtils
import org.scalatestplus.play._
import play.api.libs.json.Json
import play.api.test.Helpers._
import triplestore.Sparql

class TestSkos extends PlaySpec with OneAppPerSuite with FakeTripleStore {

  "Mapping toggle should work" in {
    cleanStart()

    // have an actor create two Skos vocabs
    val actorStore = new ActorStore
    val admin = await(actorStore.authenticate("gumby", "secret gumby")).get
    val genreInfo = await(VocabInfo.createVocabInfo(admin, "gtaa_genre"))
    val genreFile = new File(getClass.getResource("/skos/Genre.xml").getFile)
    await(ts.up.dataPutXMLFile(genreInfo.dataUri, genreFile))
    val classyInfo = await(VocabInfo.createVocabInfo(admin, "gtaa_classy"))
    val classyFile = new File(getClass.getResource("/skos/Classificatie.xml").getFile)
    await(ts.up.dataPutXMLFile(classyInfo.dataUri, classyFile))
    countGraphs must be(5)

    genreInfo.vocabulary.languages must be(List("en", "nl"))
    classyInfo.vocabulary.languages must be(List("nl"))

    // check the stats
    await(genreInfo.conceptCount) must be(117)

    // try a search
    val vocab = genreInfo.vocabulary
    def searchConceptScheme(sought: String) = vocab.search(sought, 3, Some("nl"))
    val searches: List[LabelSearch] = List(
      "nieuwsbulletin"
    ).map(searchConceptScheme)

    searches.foreach(labelSearch => println(Json.prettyPrint(Json.toJson(labelSearch))))

    val skosMappings = new VocabMappingStore(genreInfo, classyInfo)

    val genreA = "http://data.beeldengeluid.nl/gtaa/30103"
    val classyA = "http://data.beeldengeluid.nl/gtaa/24896"
    val mappingA = SkosMapping(admin, genreA, classyA)

    // toggle while checking
    await(skosMappings.toggleMapping(mappingA)) must be("added")
    await(skosMappings.getMappings) must be(Seq((genreA, classyA)))
    await(skosMappings.toggleMapping(mappingA)) must be("removed")
    await(skosMappings.getMappings) must be(Seq.empty[(String, String)])
    await(skosMappings.toggleMapping(mappingA)) must be("added")
    await(skosMappings.getMappings) must be(Seq((genreA, classyA)))

    val genreB = "http://data.beeldengeluid.nl/gtaa/30420"
    val classyB = "http://data.beeldengeluid.nl/gtaa/24903"
    val mappingB = SkosMapping(admin, genreB, classyB)
    await(skosMappings.toggleMapping(mappingB)) must be("added")
    await(skosMappings.getMappings).sortBy(_._1) must be(Seq((genreA, classyA), (genreB, classyB)))
  }

  "Histogram skosification should work" in {
    cleanStart()
    val histogramFile = new File(getClass.getResource("/skos/histogram-100.json").getFile)
    val histogramString = FileUtils.readFileToString(histogramFile, "UTF-8")
    val json = Json.parse(histogramString)
    val dsSpec = "histoskos"
    val actor = await(new ActorStore().authenticate("gumby", "pokey")).get
    val di = await(DsInfo.createDsInfo(actor, dsSpec, DsInfo.CharacterMapped, "edm"))
    val cases = Sparql.createCasesFromHistogram(di, json)
    cases.foreach(c => await(ts.up.sparqlUpdate(c.ensureSkosEntryQ)))
    val first = di.vocabulary.concepts.sortBy(_.resource.toString).head
    first.getAltLabel(Some("nl")).map(_.text) must be(Some("doek"))
    first.frequency must be(Some(111))
    first.fieldProperty must be(Some("http://schemas.delving.eu/brac/material"))

//    val histoStrings = di.vocabulary.concepts.map{ c =>
//      s"${c.getAltLabel("nl").text} ${c.frequency}"
//    }
//    println(histoStrings.mkString("\n"))
  }
}

