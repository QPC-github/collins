package util.plugins
package solr

import Solr._
import org.specs2._
import models.{Asset, AssetType, AssetMeta, Status, AssetMetaValue}
import test.ApplicationSpecification

class SolrSpec extends ApplicationSpecification {

  import AssetMeta.ValueType._
  import AssetMeta.ValueType

  "FlatSerializer" should {
    "serialize an asset" in {
      val assetTag = "solr1"
      val assetType = AssetType.Enum.ServerNode
      val status = Status.Enum.Allocated
      val meta = List(
        ("A",String, 0,"a"),
        ("B",String, 0,"b"),
        ("A",String, 1,"a1"),
        ("int", Integer, 0, "1135"),
        ("double", Double, 0, "3.1415"),
        ("bool", Boolean, 0, "false")
      )
      val asset = generateAsset(assetTag, assetType, status, meta)
      val expected = Map(
        "tag" -> SolrStringValue(assetTag),
        "status" -> SolrStringValue(status.toString),
        "assetType" -> SolrIntValue(assetType.id),
        "created" -> SolrStringValue(asset.created.toString),
        "A_meta_s" -> SolrMultiValue(SolrStringValue("a") :: SolrStringValue("a1") :: Nil),
        "B_meta_s" -> SolrStringValue("b"),
        "INT_meta_i" -> SolrIntValue(1135),
        "DOUBLE_meta_d" -> SolrDoubleValue(3.1415),
        "BOOL_meta_b" -> SolrBooleanValue(false)
      )
      (new FlatSerializer).serialize(asset) must_== expected
    }
  }

  def generateAsset(tag: String, assetType: AssetType.Enum, status: Status.Enum, metaValues: Seq[(String, ValueType, Int, String)]) = {
    val asset = Asset.create(Asset(tag, status, assetType))
    metaValues.foreach{case (name, value_type, group_id, value) =>
      val meta = AssetMeta.findOrCreateFromName(name, value_type)
      AssetMetaValue.create(AssetMetaValue(asset.id, meta.id, group_id, value))
    }
    asset
  }

}

class SolrQuerySpec extends ApplicationSpecification {

  def P = new CollinsQueryParser

  import CollinsQueryDSL._
  import AssetMeta.ValueType._

  "CollinsQueryDSL" should {
    "key vals" in {
      "int" in {
        (("foo" -> 3): SolrKeyVal) must_== SolrKeyVal("foo", SolrIntValue(3))
      }
      "bool" in {
        (("foo" -> false): SolrKeyVal) must_== SolrKeyVal("foo", SolrBooleanValue(false))
      }
      "int" in {
        (("foo" -> 3.1415): SolrKeyVal) must_== SolrKeyVal("foo", SolrDoubleValue(3.1415))
      }
      "string" in {
        (("foo" -> "bar"): SolrKeyVal) must_== SolrKeyVal("foo", SolrStringValue("bar"))
      }
    }

  }

  "CollinsQueryParser" should {
    "key-value" in {
      "string value" in {
        """foo = "bar"""".query must_== (("foo" -> "bar"): SolrKeyVal)
      }
      "int value" in {
        """foo = 3""".query must_== (("foo" -> 3): SolrKeyVal)
      }
      "double value" in {
        """foo = 3.1415""".query must_== (("foo" -> 3.1415): SolrKeyVal)
      }
      "boolean value" in {
        """foo = false""".query must_== (("foo" -> false): SolrKeyVal)
      }
      "range both" in {
        """foo = [3, 5]""".query must_== SolrKeyRange("foo", Some(SolrIntValue(3)), Some(SolrIntValue(5)))
      }
      "range opt low" in {
        """foo = [*, 5]""".query must_== SolrKeyRange("foo", None, Some(SolrIntValue(5)))
      }
      "range opt high" in {
        """foo = [3, *]""".query must_== SolrKeyRange("foo", Some(SolrIntValue(3)), None)
      }
      "range opt both" in {
        """foo = [*, *]""".query must_== SolrKeyRange("foo", None, None)
      }
    }

    "complex expressions" in {
      "simple AND" in {
        """foo = 3 AND bar = 4""".query must_== (("foo" -> 3) AND ("bar" -> 4))
      }
      "simple OR" in {
        """foo = 3 OR bar = 4""".query must_== (("foo" -> 3) OR ("bar" -> 4))
      }
      "order of operations" in {
        """foo = 4 OR bar = 4 AND baz = false""".query must_== (("foo" -> 4) OR ("bar" -> 4 AND "baz" -> false))
      }
      "arbitrary parentheses" in {
        """(((((((foo = true)))))))""".query must_== SolrKeyVal("foo", SolrBooleanValue(true))
      }
      "simple NOT" in {
        """NOT foo = 5""".query must_== CollinsQueryDSL.not("foo" -> 5)
      }
      "not OOO" in {
        """NOT foo = 5 OR bar = false""".query must_== (SolrNotOp(("foo" -> 5)) OR ("bar" -> false))
      }
      "negate complex expression" in {
        """NOT (foo = 5 AND bar = "baz")""".query must_== SolrNotOp(("foo" -> 5) AND ("bar" -> "baz"))
      }
        
    }
  }

  "CQL abstract syntax-tree" should {

    "solr query generation" in {
      "simple keyval" in {
        "foo = 3".query.toSolrQueryString must_== "foo:3"
      }
      "ANDs" in {
         """foo = 3 AND bar = "abcdef" AND baz = true""".query.toSolrQueryString must_== "foo:3 AND bar:abcdef AND baz:true"
      }
      "nested exprs" in {
        """(foo = 3 OR foo = 4) AND (bar = true OR (bar = false AND baz = 5))""".query.toSolrQueryString must_== "(foo:3 OR foo:4) AND (bar:true OR (bar:false AND baz:5))"
      }
      "support unquoted one-word strings" in {
        """foo = bar""".query must_== """foo = "bar"""".query
      }
    }

    "type checking" in {
      "keyvals" in {
        val m = AssetMeta.findOrCreateFromName("foo", Integer)
        "foo = 3".query.typeCheck must_== Right("FOO_meta_i = 3".query)
        "foo = 3.123".query.typeCheck must beAnInstanceOf[Left[String, SolrExpression]]
        "foo = true".query.typeCheck must beAnInstanceOf[Left[String, SolrExpression]]
        """foo = "3"""".query.typeCheck must beAnInstanceOf[Left[String, SolrExpression]]
      }
      "valid enum" in {
        """assetType = "SERVER_NODE"""".query.typeCheck must_== Right("assetType = 1".query)
      }
      "invalid enum" in {
        """assetType = "FOOBARRRRRR"""".query.typeCheck must beAnInstanceOf[Left[String, SolrExpression]]
      }
      "use enum id" in {
        """assetType = 1""".query.typeCheck must_== "assetType = SERVER_NODE".query.typeCheck
      }
      "AND" in {
        "foo = 3 AND foo = false".query.typeCheck must beAnInstanceOf[Left[String, SolrExpression]]
      }
      "OR" in {
        "foo = 3 OR foo = false".query.typeCheck must beAnInstanceOf[Left[String, SolrExpression]]
      }
      "range" in {
        "foo = [3, 5]".query.typeCheck must beAnInstanceOf[Right[String, SolrExpression]]
        "foo = [false, 5]".query.typeCheck must beAnInstanceOf[Left[String, SolrExpression]]
        "foo = [3, false]".query.typeCheck must beAnInstanceOf[Left[String, SolrExpression]]
      }
    }

  }
        

}
