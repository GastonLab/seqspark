import org.apache.spark.rdd.RDD
import java.io._
import org.ini4j.Ini
import scala.io.Source
import Constants._

object Utils {
  type RawVar = Variant[String]
  type Var = Variant[Byte]
  type RawVCF = RDD[RawVar]
  type VCF = RDD[Var]
  type Pair = (Int, Int)

  /** Give Worker Object a name
    * use a class to make it convenient in implicit parameters */
  case class WorkerName(name: String) extends AnyVal {
    override def toString = name
  }

  /** If RDD[Variant[Byte]\] is required but RDD[Variant[String]\]
    * is provided, convert it implicitly */
  implicit def naivelyConvertRawVcfToVcf(raw: RawVCF): VCF = {
    def make(g: String): String = {
      val s = g.split(":")
      s(0)
    }
    raw.map(v => v.transElem(make(_)).compress(Gt.conv(_)))
  }

  def runtimeRootDir(implicit ini: Ini): String = {
    val root = Option(ini.get("general", "runtimeRootDir"))
    root match {
      case Some(d) => d
      case None => "."
    }
  }

  def resultsDir(implicit ini: Ini): String = {
    "%s/results" format runtimeRootDir
  }

  def workerDir(implicit ini: Ini, name: WorkerName): String = {
    "%s/%s" format (resultsDir, name)
  }

  def sitesFile(implicit ini: Ini, name: WorkerName): String = {
    "%s/sites.raw.vcf" format workerDir
  }

  def readColumn (file: String, col: String): Array[String] = {
    val delim = Pheno.delim
    val data = Source.fromFile(file).getLines.toList
    val header = data(0).split(delim).zipWithIndex.toMap
    val idx = header(col)
    val res =
      for (line <- data.slice(1, data.length))
      yield line.split(delim)(idx)
    res.toArray
  }

  def hasColumn (file: String, col: String): Boolean = {
    val delim = Pheno.delim
    val header = Source.fromFile(file).getLines.toList
    if (header(0).split(delim).contains(col))
      true
    else
      false
  }

  def writeArray(file: String, data: Array[String]) {
    val pw = new PrintWriter(new File(file))
    data foreach (d => pw.write(d + "\n"))
    pw.close
  }

  def writeAny(file: String, data: String): Unit = {
    val pw = new PrintWriter(new File(file))
    pw.write(data + "\n")
    pw.close()
  }

  def getFam (file: String): Array[String] = {
    val delim = Pheno.delim
    val data = Source.fromFile(file).getLines.toArray
    val res =
      for (line <- data.drop(1))
      yield line.split(delim).slice(0, 6).mkString(delim)
    res
  }

  def writeRDD (data: RDD[String], file: String, head: String = ""): Unit = {
    val fw = new FileWriter(file)
    if (head != "")
      fw.write(head)
    val iterator = data.toLocalIterator
    while (iterator.hasNext) {
      val cur = iterator.next()
      fw.write(cur)
    }
    fw.close()
  }

  def peek[A](dat: RDD[A]): Unit = {
    println("There are %s records like this:" format(dat.count()))
    println(dat.takeSample(false,1)(0))
  }
}
