package services

import java.io.{PrintWriter, StringWriter}

import org.OrgContext
import play.api.Play.current
import play.api.libs.mailer.{Email, MailerPlugin}
import play.api.{Logger, Mode}

import scala.concurrent.ExecutionContext

object MailService {

  val fromNarthex = "Narthex <narthex@delving.eu>"

  private def sendMail(toOpt: Option[String], subject: String, html: String)(implicit ec: ExecutionContext): Unit = {
    if (OrgContext.MAIL_CONFIGURED) {
      toOpt.map { to =>
        val email = Email(to = Seq(to), from = fromNarthex, subject = subject, bodyHtml = Some(html))
        if (current.mode != Mode.Prod) {
          Logger.info(s"Not production mode, so this was not sent:\n$email")
        }
        else {
          MailerPlugin.send(email)
        }
      } getOrElse {
        Logger.warn(s"EMail: '$subject' not sent because there is no recipient email address available.")
      }
    }
    else {
      Logger.warn(s"EMail to $toOpt: '$subject' not sent because SMTP is not configured.")
    }
  }

  abstract class MailMessage {
    def send()(implicit ec: ExecutionContext): Unit
  }

  case class MailProcessingComplete(spec: String,
                                    ownerEmailOpt: Option[String],
                                    validString: String,
                                    invalidString: String) extends MailMessage {

    override def send()(implicit ec: ExecutionContext): Unit = sendMail(
      ownerEmailOpt,
      subject = s"Processing Complete: $spec",
      html = views.html.email.processingComplete.render(this).body
    )
  }

  case class MailDatasetError(spec: String,
                              ownerEmailOpt: Option[String],
                              message: String,
                              throwableOpt: Option[Throwable]) extends MailMessage {

    def exceptionString = throwableOpt.map { throwable =>
      val sw = new StringWriter()
      val out = new PrintWriter(sw)
      throwable.printStackTrace(out)
      sw.toString.split("\n").map(_.trim).map(line => if (line.startsWith("at ")) s"    $line" else line).mkString("\n")
    } getOrElse {
      "No exception"
    }

    override def send()(implicit ec: ExecutionContext): Unit = sendMail(
      ownerEmailOpt,
      subject = s"Failure in dataset: $spec",
      html = views.html.email.datasetError.render(this).body
    )

  }

}