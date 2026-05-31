package core.layout

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.{Arbitrary, Gen}

import core.geometry.Rect
import core.windows.{WindowId, WindowList, WindowState}
import core.input.{
  KeyBinding, KeyBindingMap, KeyEvent, KeySym, Modifiers, Pressed,
  IncreaseMasterRatio, DecreaseMasterRatio, IncreaseMasterCount, DecreaseMasterCount
}

class MasterStackSpec extends ScalaCheckSuite:

  // ── Generators ──────────────────────────────────────────────────────

  /** Generate a Rect with at least 1x1 dimensions so area properties are meaningful. */
  val genAvailableRect: Gen[Rect] =
    for
      x <- Gen.choose(0, 100)
      y <- Gen.choose(0, 100)
      w <- Gen.choose(1, 800)
      h <- Gen.choose(1, 600)
    yield Rect(x, y, w, h)

  def mkWindowList(n: Int): WindowList =
    (1 to n).foldLeft(WindowList.empty) { (wl, i) =>
      wl.add(WindowState(
        id       = WindowId(i.toLong),
        mapped   = true,
        title    = None,
        appId    = None,
        geometry = Rect(0, 0, 0, 0)
      ))
    }

  val genConfig: Gen[MasterStackConfig] =
    for
      mc    <- Gen.choose(1, 8)
      ratio <- Gen.choose(0.05, 0.95)
    yield MasterStackConfig(ratio, mc)

  /** A config paired with a window list whose size is independently chosen. */
  val genConfigAndWindows: Gen[(MasterStackConfig, WindowList)] =
    for
      cfg <- genConfig
      n   <- Gen.choose(1, 8)
    yield (cfg, mkWindowList(n))

  // ── MasterStackConfig construction ──────────────────────────────────

  test("MasterStackConfig: valid config constructs without throwing") {
    MasterStackConfig(0.55, 1)
  }

  test("MasterStackConfig: ratio <= 0.0 throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      MasterStackConfig(0.0, 1)
    }
  }

  test("MasterStackConfig: negative ratio throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      MasterStackConfig(-0.1, 1)
    }
  }

  test("MasterStackConfig: ratio >= 1.0 throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      MasterStackConfig(1.0, 1)
    }
  }

  test("MasterStackConfig: masterCount < 1 throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      MasterStackConfig(0.5, 0)
    }
  }

  // ── MasterStackLayout.arrange — structural properties (ScalaCheck) ──

  property("no-overlap: distinct window tiles do not intersect") {
    forAll(genConfigAndWindows, genAvailableRect) { case ((cfg, wl), available) =>
      val result = MasterStackLayout.arrange(cfg, wl, available)
      val tiles  = result.values.toList
      val pairs  = for a <- tiles; b <- tiles; if a != b yield (a, b)
      pairs.forall { case (a, b) => a.intersect(b).isEmpty }
    }
  }

  property("within-bounds: every tile is fully contained in available") {
    forAll(genConfigAndWindows, genAvailableRect) { case ((cfg, wl), available) =>
      val result = MasterStackLayout.arrange(cfg, wl, available)
      result.values.forall { r =>
        r.x >= available.x &&
        r.y >= available.y &&
        r.x + r.w <= available.x + available.w &&
        r.y + r.h <= available.y + available.h
      }
    }
  }

  property("full-coverage: sum of tile areas equals available area when windows > 0") {
    forAll(genConfigAndWindows, genAvailableRect) { case ((cfg, wl), available) =>
      val result = MasterStackLayout.arrange(cfg, wl, available)
      if result.isEmpty then true
      else
        val tileArea = result.values.map(r => r.w.toLong * r.h.toLong).sum
        tileArea == available.w.toLong * available.h.toLong
    }
  }

  property("determinism: calling arrange twice with identical inputs returns identical maps") {
    forAll(genConfigAndWindows, genAvailableRect) { case ((cfg, wl), available) =>
      MasterStackLayout.arrange(cfg, wl, available) ==
        MasterStackLayout.arrange(cfg, wl, available)
    }
  }

  // ── MasterStackLayout.arrange — ratio sensitivity ───────────────────

  test("ratio sensitivity: higher masterRatio yields strictly wider master column") {
    val available = Rect(0, 0, 1000, 600)
    val wl = mkWindowList(2)
    val loRatio = MasterStackConfig(0.3, 1)
    val hiRatio = MasterStackConfig(0.7, 1)
    val loResult = MasterStackLayout.arrange(loRatio, wl, available)
    val hiResult = MasterStackLayout.arrange(hiRatio, wl, available)
    // Master is the first window in ordered list
    val masterId = wl.ordered.head
    val loMasterW = loResult(masterId).w
    val hiMasterW = hiResult(masterId).w
    assert(hiMasterW > loMasterW,
      s"expected hiMasterW ($hiMasterW) > loMasterW ($loMasterW)")
  }

  // ── MasterStackLayout.arrange — edge cases ──────────────────────────

  test("empty WindowList returns empty Map") {
    val cfg    = MasterStackConfig.default
    val result = MasterStackLayout.arrange(cfg, WindowList.empty, Rect(0, 0, 1920, 1080))
    assertEquals(result, Map.empty[WindowId, Rect])
  }

  test("single mapped window covers the full available Rect") {
    val cfg  = MasterStackConfig.default
    val wl   = mkWindowList(1)
    val avail = Rect(0, 0, 1920, 1080)
    val result = MasterStackLayout.arrange(cfg, wl, avail)
    assertEquals(result.size, 1)
    assertEquals(result.values.head, avail)
  }

  test("all windows unmapped returns empty Map") {
    val cfg   = MasterStackConfig.default
    val avail = Rect(0, 0, 1920, 1080)
    val wl    = WindowList.empty
      .add(WindowState(WindowId(1L), mapped = false, None, None, Rect(0, 0, 0, 0)))
      .add(WindowState(WindowId(2L), mapped = false, None, None, Rect(0, 0, 0, 0)))
    val result = MasterStackLayout.arrange(cfg, wl, avail)
    assertEquals(result, Map.empty[WindowId, Rect])
  }

  test("floating and fullscreen windows are excluded from tiling") {
    val cfg   = MasterStackConfig(0.5, 1)
    val avail = Rect(0, 0, 1000, 600)
    val wl = WindowList.empty
      .add(WindowState(WindowId(1L), mapped = true, None, None, Rect(0, 0, 0, 0), floating = false, fullscreen = false))
      .add(WindowState(WindowId(2L), mapped = true, None, None, Rect(0, 0, 0, 0), floating = true))
      .add(WindowState(WindowId(3L), mapped = true, None, None, Rect(0, 0, 0, 0), fullscreen = true))
    val result = MasterStackLayout.arrange(cfg, wl, avail)
    assertEquals(result.keySet, Set(WindowId(1L)))
    assertEquals(result(WindowId(1L)), avail)
  }

  test("masterCount=1, 2 windows: one in master column (left), one in stack (right)") {
    val cfg    = MasterStackConfig(0.5, 1)
    val avail  = Rect(0, 0, 1000, 600)
    // masterW = (1000 * 0.5).toInt = 500
    val wl     = mkWindowList(2)
    val result = MasterStackLayout.arrange(cfg, wl, avail)
    assertEquals(result.size, 2)
    val ordered   = wl.ordered
    val masterId  = ordered.head
    val stackId   = ordered(1)
    val masterW   = (1000 * 0.5).toInt   // 500
    val stackW    = 1000 - masterW        // 500
    assertEquals(result(masterId), Rect(0, 0, masterW, 600))
    assertEquals(result(stackId),  Rect(masterW, 0, stackW, 600))
  }

  test("masterCount=2, 2 windows: both in master column, no stack; each gets half height") {
    val cfg   = MasterStackConfig(0.6, 2)
    val avail = Rect(0, 0, 1000, 600)
    val wl    = mkWindowList(2)
    val result = MasterStackLayout.arrange(cfg, wl, avail)
    assertEquals(result.size, 2)
    val ordered  = wl.ordered
    val id0      = ordered(0)
    val id1      = ordered(1)
    val tileH    = 600 / 2    // 300
    val lastH    = 600 - tileH // 300 (no remainder in this case)
    assertEquals(result(id0), Rect(0, 0,     1000, tileH))
    assertEquals(result(id1), Rect(0, tileH, 1000, lastH))
  }

  test("masterCount=1, 3 windows: master gets left half, 2 stack windows each get half the right column height") {
    val cfg   = MasterStackConfig(0.5, 1)
    val avail = Rect(0, 0, 1000, 600)
    // masterW = 500, stackW = 500
    // stack has 2 windows: tileH = 600/2 = 300, lastH = 600 - 300 = 300
    val wl     = mkWindowList(3)
    val result = MasterStackLayout.arrange(cfg, wl, avail)
    assertEquals(result.size, 3)
    val ordered  = wl.ordered
    val masterId = ordered(0)
    val stackId0 = ordered(1)
    val stackId1 = ordered(2)
    val masterW  = (1000 * 0.5).toInt   // 500
    val stackW   = 1000 - masterW        // 500
    val tileH    = 600 / 2               // 300
    val lastH    = 600 - tileH           // 300
    assertEquals(result(masterId), Rect(0,       0,     masterW, 600))
    assertEquals(result(stackId0), Rect(masterW, 0,     stackW,  tileH))
    assertEquals(result(stackId1), Rect(masterW, tileH, stackW,  lastH))
  }

  // ── New Action variants — keybinding dispatch ────────────────────────

  test("KeyBindingMap.default dispatches Super+H (0x0068) to DecreaseMasterRatio") {
    val event = KeyEvent(KeySym(0x0068), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), DecreaseMasterRatio)
  }

  test("KeyBindingMap.default dispatches Super+L (0x006c) to IncreaseMasterRatio") {
    val event = KeyEvent(KeySym(0x006c), Modifiers.Super, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), IncreaseMasterRatio)
  }

  test("KeyBindingMap.default dispatches Super+Shift+H (0x0068) to DecreaseMasterCount") {
    val event = KeyEvent(KeySym(0x0068), Modifiers.Super | Modifiers.Shift, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), DecreaseMasterCount)
  }

  test("KeyBindingMap.default dispatches Super+Shift+L (0x006c) to IncreaseMasterCount") {
    val event = KeyEvent(KeySym(0x006c), Modifiers.Super | Modifiers.Shift, Pressed)
    assertEquals(KeyBindingMap.default.dispatch(event), IncreaseMasterCount)
  }

  // ── TEST-001 regression: remainder distribution ──────────────────────

  test("remainder distribution: 3-window stack column of height 100 gives tiles 33, 33, 34") {
    // 3 stack windows in a column of height 100:
    // tileH = 100 / 3 = 33, last = 100 - 2*33 = 34
    // Use masterCount=0 trick: use 4 windows with masterCount=1 but focus on the stack
    // Actually: use available 100 px tall, masterCount=1, 4 windows (1 master, 3 stack)
    val cfg   = MasterStackConfig(0.5, 1)
    val avail = Rect(0, 0, 200, 100)
    val wl    = mkWindowList(4)
    val result = MasterStackLayout.arrange(cfg, wl, avail)
    val ordered   = wl.ordered
    val stackIds  = ordered.tail  // 3 stack windows
    val stackH    = avail.h
    val tileH     = stackH / 3   // 33
    val lastH     = stackH - 2 * tileH  // 34
    val stackW    = avail.w - (avail.w * 0.5).toInt
    val masterW   = (avail.w * 0.5).toInt
    assertEquals(result(stackIds(0)), Rect(masterW, 0,          stackW, tileH))
    assertEquals(result(stackIds(1)), Rect(masterW, tileH,      stackW, tileH))
    assertEquals(result(stackIds(2)), Rect(masterW, 2 * tileH,  stackW, lastH))
  }
