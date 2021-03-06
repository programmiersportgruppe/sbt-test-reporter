package org.programmiersportgruppe.sbt.testreporter

import java.io.File
import java.nio.file.{Files, Paths}
import java.text.{DateFormat, SimpleDateFormat}
import java.util.Date

import org.programmiersportgruppe.sbt.testreporter.LatestResultMode.Symlink
import org.programmiersportgruppe.sbt.testreporter.ReportFormat._
import org.programmiersportgruppe.sbt.testreporter.Utilities._
import sbt.Keys._
import sbt._
import sbt.testing.{Event => TEvent, Status => TStatus, _}

import scala.collection.mutable.ListBuffer
import scala.util.DynamicVariable

object TabularTestReporterPlugin extends AutoPlugin {


    object autoImport {
        val Html = ReportFormat.Html
        val WhiteSpaceDelimited = ReportFormat.WhiteSpaceDelimited
        val Json = ReportFormat.Json

        lazy val testReportFormats = settingKey[Set[ReportFormat]]("report formats")

        lazy val latestResultsMode = settingKey[LatestResultMode]("create symlinks or copies for latest results")

        val Symlink = LatestResultMode.Symlink
        val Copy = LatestResultMode.Copy
        val Skip = LatestResultMode.Skip
    }

    override lazy val projectSettings = Seq(
        autoImport.testReportFormats := Set(WhiteSpaceDelimited, Html, Json),
        autoImport.latestResultsMode := Symlink,
        testListeners += new TabularTestReporter(target.value.getAbsolutePath, autoImport.testReportFormats.value, autoImport.latestResultsMode.value)
    )

    override val trigger = AllRequirements
}

class TabularTestReporter(val outputDir: String, formats: Set[ReportFormat], latestResultMode: LatestResultMode) extends TestsListener {
    private val timeStamp: Date = new Date()

    val timeStampFileName: String = new SimpleDateFormat("YMMdd-HHmmss").format(timeStamp)

    val timeStampIso8601: String = {
        //val tz = TimeZone.getTimeZone("UTC");
        val df: DateFormat  = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        //df.setTimeZone(tz)
        df.format(timeStamp)
    }

    /** The dir in which we put all result files. Is equal to the given dir + "/test-reports" */
    val targetDir = new File(outputDir + "/test-reports/")

    var results: Seq[TestSummary] = Seq()

    /**
     * Gathers data for one Test Suite. We map test groups to TestSuites.
     */
    class TestSuite(val suiteName: String) {
        val events: ListBuffer[TEvent] = new ListBuffer()
        val start: Long = System.currentTimeMillis

        def addEvent(e: TEvent) = events += e

        /**
         * Stops the time measuring and emits the results for
         * All tests collected so far.
         */
        def stop(): Seq[TestSummary] = {
            var end = System.currentTimeMillis

            val durationSetup = {
                val totalDurationTestsWithSetup = end - start
                val totalDurationTestsWithoutSetup = events.map(_.duration).filter(_ >= 0).sum
                totalDurationTestsWithSetup - totalDurationTestsWithoutSetup
            }

            def wasRun(event: TEvent): Boolean = !Set(TStatus.Ignored, TStatus.Skipped).contains(event.status)

            val numberOfTestsRun = events.count(wasRun)

            val setupTimePerTestRun = durationSetup.toDouble / numberOfTestsRun

            for (e <- events) yield {

                val className = e.fullyQualifiedName()

                val name = e.selector match {
                    case t: TestSelector => t.testName()
                    case n: NestedTestSelector => n.testName()
                    case _: SuiteSelector => "(suite level failure)"
                    case _ => "TOSTRING:" + e.selector().toString
                }

                val rawDuration = math.max(0, e.duration)  // e.duration can be -1, if no duration was available
                val durationWithSetup: Double = rawDuration + (if (wasRun(e)) setupTimePerTestRun else 0)

                val statusText = e.status.toString.toUpperCase

                val exceptionOpt: Option[Throwable] = if (e.throwable.isDefined) {
                    Some(e.throwable.get)
                } else {
                    None
                }

                val stackTrace = exceptionOpt.map(ex => ex.stackTrace)

                val errorMessage = e.throwable match {
                    case t if t.isDefined =>
                        Option(t.get.getMessage).fold(t.get.getClass.getName)(_.split("\n")(0)) // this logic should move up
                    case _ =>
                        ""
                }

                TestSummary(
                    timeStamp,
                    statusText,
                    durationWithSetup / 1000.0,
                    rawDuration / 1000.0,
                    className,
                    name,
                    new Date(),
                    errorMessage,
                    stackTrace.getOrElse("")
                )
            }
        }
    }

    /** The currently running test suite */
    var testSuite: DynamicVariable[TestSuite] = new DynamicVariable[TestSuite](null)

    /** Creates the output Dir */
    override def doInit(): Unit = {
        targetDir.mkdirs()
    }

    /**
     * Starts a new, initially empty Suite with the given name.
     */
    override def startGroup(name: String) {
        testSuite.value = new TestSuite(name)
    }

    override def testEvent(event: TestEvent): Unit = {
        for (e <- event.detail) {
            testSuite.value.addEvent(e)
        }
    }

    override def endGroup(name: String, t: Throwable): Unit = {
        this.synchronized {
            val timestamp = new Date()
            val summary: TestSummary =TestSummary(
                timestamp,
                "ERROR",
                0,
                0,
                name,
                "(suite level failure)",
                timestamp,
                Option(t.getMessage).map(_.split("\n")(0)).getOrElse(t.getClass.getName), // this logic should move up
                t.stackTrace
            )

            results = results :+ summary
        }
        System.err.println("Throwable escaped the test run of '" + name + "': " + t)
        t.printStackTrace(System.err)
    }

    override def endGroup(name: String, result: TestResult): Unit = {
        this.synchronized {
            results ++= testSuite.value.stop()
        }
    }


    /** Does nothing, as we write each file after a suite is done. */
    override def doComplete(finalResult: TestResult): Unit = {
        println("formats: " + formats)
        formats.foreach((format: ReportFormat) =>{

            val resultPath: String = new sbt.File(targetDir, s"test-results-$timeStampFileName.${format.extension}").getAbsolutePath
            format match {
                case WhiteSpaceDelimited =>
                    (results.map(result => result.toColumns.mkString(" ")).mkString("\n") + "\n").save(resultPath)
                case Html => scala.xml.XML.save(resultPath, new HtmlFormatter(results).htmlReport, enc = "UTF-8", xmlDecl = true)
                case Json =>
                    (results.map(_.toJson).mkString("\n") + "\n").save(resultPath)
            }
            latestResultMode.updateLatestResult(resultPath, new File(outputDir, s"test-results-latest.${format.extension}"))
        } )
    }

    /** Returns None */
    override def contentLogger(test: TestDefinition): Option[ContentLogger] = None
}
