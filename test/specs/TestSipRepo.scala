package specs

import java.io.File

import dataset.StagingRepo.StagingFacts
import dataset.{SipRepo, StagingRepo}
import harvest.Harvesting.HarvestType
import org.apache.commons.io.FileUtils
import org.scalatest.{FlatSpec, Matchers}
import record.PocketParser.Pocket
import services.ProgressReporter

import scala.xml.XML

class TestSipRepo extends FlatSpec with Matchers {

  "A SipRepo" should "handle a harvest sip" in {

    val home = new File(getClass.getResource("/sip").getFile)
    val sipRepo = new SipRepo(new File(home, "sips"))
    val stagingSourceDir = new File(home, "staging")
    val stagingDir = new File("/tmp/test-sip-repo-staging")

    val sipFileOpt = sipRepo.latestSipFile
    sipFileOpt.isDefined should be(true)

    sipFileOpt.foreach { sipFile =>

      sipFile.spec should be(Some("brabant-collectie-prent"))

      sipFile.uniqueElementPath should be(Some("/harvest/OAI-PMH/ListRecords/record/metadata/arno:document/arno:document-admin/arno:doc_id"))

      sipFile.schemaVersionOpt.isDefined should be(true)

      val stagingRepo = StagingRepo.createClean(stagingDir, StagingFacts(HarvestType.PMH))
      FileUtils.copyDirectory(stagingSourceDir, stagingDir)

      var mappedPockets = List.empty[Pocket]

      sipFile.createSipMapper.map { sipMapper =>
        def pocketCatcher(pocket: Pocket): Unit = {
          var mappedPocket = sipMapper.map(pocket)
          mappedPockets = mappedPocket.get :: mappedPockets
        }
        stagingRepo.parsePockets(pocketCatcher, ProgressReporter())
      }

      mappedPockets.size should be (25)

      val head = XML.loadString(mappedPockets.head.text)

      println(head)

      val eglise = "L'Eglise collegiale de Notre Dame a Breda.Harrewijn fecit"

      val titleText = (head \ "Description" \ "title").filter(_.prefix == "dc").text.trim

      titleText should be(eglise)

    }
  }
}
