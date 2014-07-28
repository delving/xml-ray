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

package controllers

import controllers.Application.OkFile
import play.api.libs.json._
import play.api.mvc._
import services._

object APIController extends Controller with TreeHandling with RecordHandling {

  def indexJSON(apiKey: String, email: String, fileName: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        OkFile(repo.fileRepo(fileName).index)
      }
      else {
        Unauthorized
      }
    }
  }

  def indexText(apiKey: String, email: String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        repo.fileRepo(fileName).indexText(path) match {
          case None => NotFound(Json.obj("path" -> path))
          case Some(file) => OkFile(file)
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def uniqueText(apiKey: String, email: String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        repo.fileRepo(fileName).uniqueText(path) match {
          case None => NotFound(Json.obj("path" -> path))
          case Some(file) => OkFile(file)
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def histogramText(apiKey: String, email: String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        repo.fileRepo(fileName).histogramText(path) match {
          case None => NotFound(Json.obj("path" -> path))
          case Some(file) => OkFile(file)
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def rawRecord(apiKey: String, email: String, fileName: String, id: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        val fileRepo = repo.fileRepo(fileName)
        val storedRecord: String = fileRepo.getRecord(id)
        Ok(scala.xml.XML.loadString(storedRecord))
      }
      else {
        Unauthorized
      }
    }
  }

  def enrichedRecord(apiKey: String, email: String, fileName: String, id: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        val fileRepo = repo.fileRepo(fileName)
        val filePrefix: String = s"${email.replaceAllLiterally(".", "_")}/$fileName"
        // todo: cache the mappings for a few minutes
        val mappings = fileRepo.getMappings.map(m => (m.source, TargetConcept(m.target, m.vocabulary, m.prefLabel))).toMap
        val storedRecord: String = fileRepo.getRecord(id)
        if (storedRecord.isEmpty) throw new RuntimeException("Hey no record!")
        val recordRoot = (fileRepo.getDatasetInfo \ "delimit" \ "recordRoot").text
        val parser = new StoredRecordParser(filePrefix, recordRoot, mappings)
        val record = parser.parse(storedRecord)
        Ok(scala.xml.XML.loadString(record.text.toString()))
      }
      else {
        Unauthorized
      }
    }
  }

  def mappings(apiKey: String, email: String, fileName: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        val fileRepo = repo.fileRepo(fileName)
        val mappings = fileRepo.getMappings
        val reply =
          <mappings>
            {mappings.map { m =>
            <mapping>
              <source>
                {m.source}
              </source>
              <target>
                {m.target}
              </target>
            </mapping>
          }}
          </mappings>
        Ok(reply)
      }
      else {
        Unauthorized("Unauthorized")
      }
    }
  }

  private def checkKey(email: String, fileName: String, apiKey: String) = {
    // todo: mindless so far, and do it as an action like in Security.scala
    val toHash: String = s"narthex|$email|$fileName"
    val hash = toHash.hashCode
    val expected: String = Integer.toString(hash, 16).substring(1)
    val correct = expected == apiKey
    if (!correct) {
      println(s"expected[$expected] got[$apiKey] toHash[$toHash]")
    }
    correct
  }
}
