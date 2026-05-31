package core.resource

import munit.FunSuite

class ResourceScopeSpec extends FunSuite:

  test("close runs cleanups in reverse registration order (LIFO)") {
    val order = collection.mutable.ArrayBuffer.empty[String]
    val scope = ResourceScope()
    scope.onClose(order += "first")
    scope.onClose(order += "second")
    scope.onClose(order += "third")
    scope.close()
    assertEquals(order.toList, List("third", "second", "first"))
  }

  test("acquire returns the resource and registers cleanup") {
    var released = false
    val scope = ResourceScope()
    val value = scope.acquire(42)(_ => released = true)
    assertEquals(value, 42)
    assert(!released)
    scope.close()
    assert(released)
  }

  test("close is idempotent — second close does not re-run cleanups") {
    var count = 0
    val scope = ResourceScope()
    scope.onClose(count += 1)
    scope.close()
    scope.close()
    assertEquals(count, 1)
  }

  test("empty scope can be closed safely") {
    ResourceScope().close()
  }

  test("multiple acquire resources cleaned up in LIFO order") {
    val order = collection.mutable.ArrayBuffer.empty[Int]
    val scope = ResourceScope()
    val a = scope.acquire(1)(v => order += v)
    val b = scope.acquire(2)(v => order += v)
    val c = scope.acquire(3)(v => order += v)
    assertEquals(a, 1)
    assertEquals(b, 2)
    assertEquals(c, 3)
    scope.close()
    assertEquals(order.toList, List(3, 2, 1))
  }

  test("close runs all cleanups even if one throws, rethrowing the first failure with others suppressed") {
    val order = collection.mutable.ArrayBuffer.empty[String]
    val scope = ResourceScope()
    scope.onClose(order += "first")
    scope.onClose {
      order += "second-before-throw"
      throw new RuntimeException("boom-second")
    }
    scope.onClose(order += "third")
    scope.onClose {
      order += "fourth-before-throw"
      throw new RuntimeException("boom-fourth")
    }

    val ex = intercept[RuntimeException](scope.close())

    assertEquals(order.toList,
      List("fourth-before-throw", "third", "second-before-throw", "first"))
    assertEquals(ex.getMessage, "boom-fourth")
    assertEquals(ex.getSuppressed.toList.map(_.getMessage), List("boom-second"))
  }

  test("close is idempotent after a throwing cleanup — second close is a no-op") {
    val scope = ResourceScope()
    scope.onClose(throw new RuntimeException("boom"))
    intercept[RuntimeException](scope.close())
    scope.close()
  }
