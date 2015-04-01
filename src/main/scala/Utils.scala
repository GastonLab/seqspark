import org.apache.spark.rdd.RDD
import java.io._
import org.apache.spark.SparkContext._

object Utils {
  def inter(vars: RDD[Variant[String]], batch: Array[Int]) {
    //type Cnt = Map[Int, Int]
    type Cnt = (Double, Double)
    def make(g: String): Cnt = {
      val s = g split (":")
      //val gd = s(3).toInt
      //val gq = s(4).toDouble
      if (s(0) == "./.") (0.0, 1.0) else (1.0, 1.0)
    }
    def interP(v: Variant[String]): Boolean = {
      val cntB = Count[Cnt](v)(make).collapseByBatch(batch)
      val min = cntB.values reduce ((a, b) => if (a._1/a._2 < b._1/b._2) a else b)
      if (min._1/min._2 < 0.9) false else true
    }
    def interV (vars: RDD[Variant[String]]): RDD[Variant[String]] =
      vars filter (v => interP(v))

    def writeInter (vars: RDD[Variant[String]]) {
      val file = "Bspec.txt"
      val pw = new PrintWriter(new File(file))
      val res = vars filter (v => ! interP(v)) map (x => (x.chr, x.pos, x.filter))
      pw.write("chr\tpos\tfilter\n")
      res.collect foreach { case (c, p, f) => pw.write("%s\t%d\t%s\n" format (c, p, f)) }
      pw.close
    }
    writeInter(vars)
    //interV(vars)
  }

  def countByGD(vars: RDD[Variant[String]], file: String, batch: Array[Int]) {
    type Cnt = Map[(Int, Int), Int]
    type Bcnt = Map[Int, Cnt]
    def make(g: String): Cnt = {
      val s = g split (":")
      if (s(0) == "./.") Map((0, -1) -> 0) else Map((s(3).toInt, s(4).toInt) -> 1)
    }
    def cnt(vars: RDD[Variant[String]]): Array[(String, Bcnt)] = {
      val all = vars map (
        v => (v.filter, Count[Cnt](v)(make).collapseByBatch(batch)))
      val res = all reduceByKey ((a, b) => Count.addByBatch[Cnt](a, b))
      res.collect
    }
    def writeCnt(cnt: Array[(String, Bcnt)], file: String) {
      val pw = new PrintWriter(new File(file))
      pw.write("qc\tbatch\tgd\tgq\tcnt\n")
      for ((qc, map1) <- cnt)
        for ((batch, map2) <- map1)
          for (((gd, gq), c) <- map2)
            pw.write("%s\t%d\t%d\t%d\t%d\n" format (qc, batch, gd, gq, c))
      pw.close
    }
    writeCnt(cnt(vars), file)
  }

  def countByFilter(vars: RDD[Variant[String]], file: String) {
    val pw = new PrintWriter(new File(file))
    val cnts: RDD[(String, Int)] =
      vars map (v => (v.filter, 1)) reduceByKey ((a: Int, b: Int) => a + b)
    cnts.collect foreach {case (f: String, c: Int) => pw.write(f + ": " + c + "\n")}
    pw.close
  }

  def computeMis(vars: RDD[Variant[String]], file: String, qc: Boolean, group: Map[(String, Int), String] = null) = {
    val grp = vars.map(v => (v.chr, v.pos) -> v.filter).collect.toMap
    def pass(g: String): Boolean = {
      val s = g.split(":")
      if (s(0) == "./." || (qc && s(1) != "PASS")) false else true
    }
    def make(g: String): Int = {
      if (pass(g)) 1 else 0
    }
    def cnt(vars: RDD[Variant[String]]): Map[(String, Int), Int] = {
      vars.map(v => (v.chr, v.pos) -> v.geno.map(g => make(g)).reduce((a,b) => a + b)).collect.toMap
    }
    def writeMis(c: Map[(String, Int), Int], file: String) {
      val pw = new PrintWriter(new File(file))
      pw.write("chr\tpos\tcnt\tqc\tfreq\n")
      c.foreach {case ((chr, pos), cnt) => pw.write("%s\t%d\t%d\t%s\t%s\n" format(chr, pos, cnt,grp((chr, pos)), group((chr, pos)) ))}
      pw.close
    }
    writeMis(cnt(vars), file)
  }

  def computeMaf(vars: RDD[Variant[String]], qc: Boolean) = {
    def pass(g: String): Boolean = {
      val s = g.split(":")
      if (s(0) == "./.") false else true}
    def pass1(g: String) = {
      val s = g.split(":")
      if (s(0) == "./." || (qc && s(1) != "PASS")) false else true
    }
    def make(g: String): Gq = {
      val s = g.split(":")
      if (pass(g)){
        val a = s(0).split("/").map(_.toInt)
        new Gq((a.sum, 2))
      }else
        new Gq((0, 0))
    }
    def maf(vars: RDD[Variant[String]]): Map[(String, Int), String] = {
      val m = vars.map(v => {val maf = v.geno.map(g => make(g)).reduce((a, b) => a + b); val cat = if (maf.cnt == 0) "Nocall" else if (maf.total == 1 || maf.cnt - maf.total == 1) "Singleton" else if (maf.mean < 0.01 || maf.mean > 0.99) "Rare" else if (maf.mean < 0.05 || maf.mean > 0.95) "Infrequent" else "Common"; (v.chr, v.pos, cat)}).collect
      m.map(v => (v._1, v._2) -> v._3).toMap
    }
    maf(vars)
  }

  def computeGQ(vars: RDD[Variant[String]], file: String, ids: Array[String], group: Map[(String, Int), String] = null) {
    val grp = Option(group).getOrElse(vars.map(v => (v.chr, v.pos) -> v.filter).collect.toMap)
    def pass(g: String): Boolean = {
      val s = g.split(":")
      if (s(0) == "./.") false else true}
    def make(g: String): Gqmap = {
      val s = g.split(":")
      if (pass(g))
        new Gqmap(scala.collection.mutable.Map[String, Gq](s(0) -> new Gq((s(4).toDouble, 1))))
      else
        new Gqmap(scala.collection.mutable.Map[String, Gq]("./." -> new Gq((0, 1))))}
    def add (a: Gtinfo[Gqmap], b: Gtinfo[Gqmap]) = {
      for (i <- Range(0, a.length))
        a.elems(i) += b.elems(i)
      a}

    def gq(vars: RDD[Variant[String]]): Array[(String, Gtinfo[Gqmap])] = {
      vars.map(v => (grp((v.chr, v.pos)), new Gtinfo[Gqmap]{val elems =v.geno.map(g => make(g))})).reduceByKey((a: Gtinfo[Gqmap], b: Gtinfo[Gqmap]) => add(a, b)).collect
    }
    def writeGQ(gq: Array[(String, Gtinfo[Gqmap])], file: String) {
      val pw = new PrintWriter(new File(file))
      val delim = "\t"
      //val header = gq.map(x => x._2.elems(0).headers.map(h => x._1 + h).mkString(delim)).mkString(delim)
      val header = "group\tsample\tgt\ttotal\tcnt\n"
      pw.write(header)
      for (j <- Range(0, gq.length)) {
        for (i <- Range(0,gq(0)._2.length)) {
          val g =  gq(j)._2.elems(i).elem
          for (k <- g.keys
            if (k != "./."))
            pw.write("%s\t%s\t%s\t%s\t%s\n".format(gq(j)._1, ids(i),k, g(k).elem._1, g(k).elem._2))
        }
      }
      pw.close
    }
    writeGQ(gq(vars), file)
  }

  def callRate (vars: RDD[Variant[String]], file: String, id: Array[String]) {
    type Cnt = (Double, Double)
    
    def make (g: String): Cnt = {
      val s = g.split(":")
      //if (s(0) == "./." || s(1).toDouble < 8.0 || s(2).toDouble < 20.0) (0.0, 1.0) else (1.0, 1.0)
      if (s(0) == "./.") (0.0, 1.0) else (1.0, 1.0)
    }

    def cnt (vars: RDD[Variant[String]]): Count[Cnt] = {
      vars map (v => Count[Cnt](v)(make)) reduce ((a: Count[Cnt], b: Count[Cnt]) => a add b)
    }

    def writeCnt (c: Count[Cnt], file: String, id: Array[String]) {
      val pw = new PrintWriter(new File(file))
      pw.write("id\tcall\ttotal\trate\n")
      for ((i, (cnt, total)) <- id zip c.geno )
        pw.write("%s\t%s\t%s\t%.4f\n" format (i, cnt, total, cnt/total))
      pw.close
    }
    writeCnt(cnt(vars), file, id)
  }
}
