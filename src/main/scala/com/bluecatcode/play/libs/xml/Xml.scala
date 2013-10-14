package com.bluecatcode.play.libs.xml

import scala.collection._
import scala.util.Try
import scala.xml.Attribute
import com.bluecatcode.play.libs.soap.SOAPDate

trait XmlReader[T] {
  def read(x: xml.NodeSeq): Option[T]
}

trait XmlWriter[-T] {
  def write(t: T, base: xml.NodeSeq): xml.NodeSeq
}

trait XmlConverter[T] extends XmlReader[T] with XmlWriter[T]

object Xml extends Xml

trait Xml {
  def toXml[T](t: T, base: xml.NodeSeq = xml.NodeSeq.Empty)(implicit w: XmlWriter[T]): xml.NodeSeq = w.write(t, base)

  def fromXml[T](x: xml.NodeSeq)(implicit r: XmlReader[T]): Option[T] = r.read(x)
}

object BasicReaders extends BasicReaders

trait BasicReaders {

  import scala.util.control.Exception._

  implicit object StringReader extends XmlReader[String] {
    def read(x: xml.NodeSeq): Option[String] = if (x.isEmpty) None else Some(x.text)
  }

  implicit object IntReader extends XmlReader[Int] {
    def read(x: xml.NodeSeq): Option[Int] = if (x.isEmpty) None else catching(classOf[NumberFormatException]) opt x.text.toInt
  }

  implicit object LongReader extends XmlReader[Long] {
    def read(x: xml.NodeSeq): Option[Long] = if (x.isEmpty) None else catching(classOf[NumberFormatException]) opt x.text.toLong
  }

  implicit object ShortReader extends XmlReader[Short] {
    def read(x: xml.NodeSeq): Option[Short] = if (x.isEmpty) None else catching(classOf[NumberFormatException]) opt x.text.toShort
  }

  implicit object FloatReader extends XmlReader[Float] {
    def read(x: xml.NodeSeq): Option[Float] = if (x.isEmpty) None else catching(classOf[NumberFormatException]) opt x.text.toFloat
  }

  implicit object DoubleReader extends XmlReader[Double] {
    def read(x: xml.NodeSeq): Option[Double] = if (x.isEmpty) None else catching(classOf[NumberFormatException]) opt x.text.toDouble
  }

  implicit object BooleanReader extends XmlReader[Boolean] {
    def read(x: xml.NodeSeq): Option[Boolean] = if (x.isEmpty) None else Try(x.text.toBoolean).toOption
  }

}

object SpecialReaders extends SpecialReaders

trait SpecialReaders {
  implicit def OptionReader[T](implicit r: XmlReader[T]) = new XmlReader[Option[T]] {
    def read(x: xml.NodeSeq): Option[Option[T]] = {
      x.collectFirst {
        case e: xml.Elem => e
      }.map {
        e =>
          if (e.attributes.exists {
            a => a.key == "nil" && a.value.text == "true"
          }) None
          else r.read(e)
      } orElse Some(None)
    }
  }

  import scala.language.higherKinds

  implicit def traversableReader[F[_], A](implicit bf: generic.CanBuildFrom[F[_], A, F[A]], r: XmlReader[A]) = new XmlReader[F[A]] {
    def read(x: xml.NodeSeq): Option[F[A]] = {
      val builder = bf()
      x.foreach {
        n => r.read(n).foreach {
          builder += _
        }
      }
      Some(builder.result())
    }
  }

  implicit def mapReader[K, V](implicit rk: XmlReader[K], rv: XmlReader[V]): XmlReader[collection.immutable.Map[K, V]] = new XmlReader[collection.immutable.Map[K, V]] {
    def read(x: xml.NodeSeq): Option[collection.immutable.Map[K, V]] = {
      Some(x.collect {
        case e: xml.Elem =>
          for (
            k <- Xml.fromXml[K](e \ "key");
            v <- Xml.fromXml[V](e \ "value")
          ) yield k -> v
      }.filter(_.isDefined).map(_.get).toMap[K, V])
    }
  }

  implicit object SOAPDateReader extends XmlReader[SOAPDate] {
    def read(x: xml.NodeSeq): Option[SOAPDate] = if (x.isEmpty) None else Some(SOAPDate(x.text))
  }
}

object BasicWriters extends BasicWriters

trait BasicWriters {

  implicit object StringWriter extends XmlWriter[String] {
    def write(s: String, base: xml.NodeSeq): xml.NodeSeq = base.collectFirst {
      case e: xml.Elem => e.copy(child = xml.Text(s))
    }.getOrElse(xml.Text(s))
  }

  implicit object IntWriter extends XmlWriter[Int] {
    def write(s: Int, base: xml.NodeSeq): xml.NodeSeq = StringWriter.write(s.toString, base)
  }

  implicit object LongWriter extends XmlWriter[Long] {
    def write(s: Long, base: xml.NodeSeq): xml.NodeSeq = StringWriter.write(s.toString, base)
  }

  implicit object FloatWriter extends XmlWriter[Float] {
    def write(s: Float, base: xml.NodeSeq): xml.NodeSeq = StringWriter.write(s.toString, base)
  }

  implicit object ShortWriter extends XmlWriter[Short] {
    def write(s: Short, base: xml.NodeSeq): xml.NodeSeq = StringWriter.write(s.toString, base)
  }

  implicit object DoubleWriter extends XmlWriter[Double] {
    def write(s: Double, base: xml.NodeSeq): xml.NodeSeq = StringWriter.write(s.toString, base)
  }

  implicit object BooleanWriter extends XmlWriter[Boolean] {
    def write(s: Boolean, base: xml.NodeSeq): xml.NodeSeq = StringWriter.write(s.toString, base)
  }

}

object SpecialWriters extends SpecialWriters

trait SpecialWriters {

  val xsiNS = xml.NamespaceBinding("xsi", "http://www.w3.org/2001/XMLSchema-instance", xml.TopScope)

  implicit def optionWriter[T](implicit writer: XmlWriter[T]) = new XmlWriter[Option[T]] {
    def write(option: Option[T], base: xml.NodeSeq) = {
      option match {
        case None => base.collectFirst {
          case e: xml.Elem => e.copy(scope = xsiNS) % Attribute("xsi", "nil", "true", xml.Null)
        }.getOrElse(xml.NodeSeq.Empty)
        case Some(x) => writer.write(x, base)
      }
    }
  }

  implicit def traversableWriter[T](implicit w: XmlWriter[T]) = new XmlWriter[Traversable[T]] {
    def write(t: Traversable[T], base: xml.NodeSeq) = {
      t.foldLeft(xml.NodeSeq.Empty)((acc, n) => acc ++ w.write(n, base))
    }
  }

  implicit def mapWriter[K, V](implicit kw: XmlWriter[K], vw: XmlWriter[V]) = new XmlWriter[Map[K, V]] {
    def write(m: Map[K, V], base: xml.NodeSeq) = {
      m.foldLeft(xml.NodeSeq.Empty) {
        (acc, n) =>
          base.collectFirst {
            case e: xml.Elem =>
              e.copy(child = kw.write(n._1, <key/>) ++ vw.write(n._2, <value/>))
          }.map(acc ++ _).getOrElse(acc)
      }
    }
  }

  implicit object SOAPDateWriter extends XmlWriter[SOAPDate] {
    def write(d: SOAPDate, base: xml.NodeSeq): xml.NodeSeq = BasicWriters.StringWriter.write(d.toString, base)
  }
}
