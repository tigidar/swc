package core.windows

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.{Arbitrary, Gen}
import core.geometry.Rect

class WindowsSpec extends ScalaCheckSuite:

  // ── Helpers ─────────────────────────────────────────────────────────

  private def mkState(id: WindowId, mapped: Boolean = true): WindowState =
    WindowState(id, mapped, title = None, appId = None, geometry = Rect(0, 0, 100, 100))

  // ── WindowId ────────────────────────────────────────────────────────

  test("WindowId: two IDs from different Longs are not equal") {
    assertNotEquals(WindowId(1L), WindowId(2L))
  }

  test("WindowId: same Long gives equal IDs") {
    assertEquals(WindowId(42L), WindowId(42L))
  }

  // ── WindowState ─────────────────────────────────────────────────────

  test("WindowState: equality") {
    val id = WindowId(1L)
    val s1 = mkState(id)
    val s2 = mkState(id)
    assertEquals(s1, s2)
  }

  test("WindowState: mapped field is set explicitly") {
    val id = WindowId(1L)
    val s = mkState(id, mapped = false)
    assertEquals(s.mapped, false)
  }

  // ── WindowList.add ──────────────────────────────────────────────────

  test("WindowList.add: adding a window makes it retrievable by get(id)") {
    val id = WindowId(1L)
    val state = mkState(id)
    val list = WindowList.empty.add(state)
    assertEquals(list.get(id), Some(state))
  }

  test("WindowList.add: ordered list contains the id") {
    val id = WindowId(1L)
    val list = WindowList.empty.add(mkState(id))
    assert(list.ordered.contains(id))
  }

  // ── WindowList.remove ───────────────────────────────────────────────

  test("WindowList.remove: removing an existing window removes it from get and ordered") {
    val id = WindowId(1L)
    val list = WindowList.empty.add(mkState(id)).remove(id)
    assertEquals(list.get(id), None)
    assert(!list.ordered.contains(id))
  }

  test("WindowList.remove: removing a non-existent id is a no-op") {
    val id = WindowId(99L)
    val list = WindowList.empty.remove(id)
    assertEquals(list.get(id), None)
  }

  // ── WindowList.setMapped ────────────────────────────────────────────

  test("WindowList.setMapped: changes the mapped field") {
    val id = WindowId(1L)
    val list = WindowList.empty.add(mkState(id, mapped = false)).setMapped(id, true)
    assertEquals(list.get(id).map(_.mapped), Some(true))
  }

  test("WindowList.setMapped: id not in list is a no-op") {
    val id = WindowId(99L)
    val list = WindowList.empty.setMapped(id, true)
    assertEquals(list.get(id), None)
  }

  // ── WindowList.ordered ──────────────────────────────────────────────

  test("WindowList.ordered: returns most-recent-first (insertion order, newest first)") {
    val id1 = WindowId(1L)
    val id2 = WindowId(2L)
    val id3 = WindowId(3L)
    val list = WindowList.empty
      .add(mkState(id1))
      .add(mkState(id2))
      .add(mkState(id3))
    assertEquals(list.ordered, List(id3, id2, id1))
  }

  // ── FocusModel initial state ─────────────────────────────────────────

  test("FocusModel initial state: focused is None") {
    assertEquals(FocusModel.empty.focused, None)
  }

  // ── FocusModel.focus ────────────────────────────────────────────────

  test("FocusModel.focus: focusing an existing mapped window sets focused to Some(id)") {
    val id = WindowId(1L)
    val windows = WindowList.empty.add(mkState(id, mapped = true))
    val fm = FocusModel.empty.focus(id, windows)
    assertEquals(fm.focused, Some(id))
  }

  test("FocusModel.focus: focusing a non-existent window id leaves focused unchanged") {
    val id = WindowId(99L)
    val windows = WindowList.empty
    val fm = FocusModel.empty.focus(id, windows)
    assertEquals(fm.focused, None)
  }

  test("FocusModel.focus: focusing an unmapped window leaves focused unchanged") {
    val id = WindowId(1L)
    val windows = WindowList.empty.add(mkState(id, mapped = false))
    val fm = FocusModel.empty.focus(id, windows)
    assertEquals(fm.focused, None)
  }

  // ── FocusModel.unfocus ──────────────────────────────────────────────

  test("FocusModel.unfocus: removing focus for the currently focused window clears it and selects next") {
    val id1 = WindowId(1L)
    val id2 = WindowId(2L)
    // id2 added first, id1 added second => ordered = [id1, id2] (newest first)
    val windows = WindowList.empty
      .add(mkState(id2, mapped = true))
      .add(mkState(id1, mapped = true))
    val fm = FocusModel.empty.focus(id1, windows).unfocus(id1, windows)
    // After unfocusing id1, the next mapped window from ordered (excluding id1) is id2
    assertEquals(fm.focused, Some(id2))
  }

  test("FocusModel.unfocus: if no other windows, focused becomes None") {
    val id = WindowId(1L)
    val windows = WindowList.empty.add(mkState(id, mapped = true))
    val fm = FocusModel.empty.focus(id, windows).unfocus(id, windows)
    assertEquals(fm.focused, None)
  }

  test("FocusModel.unfocus: called for a window that is not currently focused is a no-op") {
    val id1 = WindowId(1L)
    val id2 = WindowId(2L)
    val windows = WindowList.empty
      .add(mkState(id2, mapped = true))
      .add(mkState(id1, mapped = true))
    val fm = FocusModel.empty.focus(id1, windows)
    val fm2 = fm.unfocus(id2, windows) // id2 is not focused
    assertEquals(fm2.focused, Some(id1)) // unchanged
  }

  // ── Properties ──────────────────────────────────────────────────────

  test("Focus invariant: if focused is Some(id) then id exists in WindowList and is mapped") {
    // Build a sequence of adds/removes/focus/unfocus and check the invariant holds
    val id1 = WindowId(1L)
    val id2 = WindowId(2L)
    var windows = WindowList.empty
    var fm = FocusModel.empty

    // add id1
    windows = windows.add(mkState(id1, mapped = false))
    // add id2
    windows = windows.add(mkState(id2, mapped = true))
    // focus id1 (unmapped — should fail)
    fm = fm.focus(id1, windows)
    checkFocusInvariant(fm, windows)
    // map id1 and focus
    windows = windows.setMapped(id1, true)
    fm = fm.focus(id1, windows)
    checkFocusInvariant(fm, windows)
    // remove id1 — note: the invariant check is on the model state, so
    // after remove we unfocus then verify
    windows = windows.remove(id1)
    fm = fm.unfocus(id1, windows)
    checkFocusInvariant(fm, windows)
  }

  private def checkFocusInvariant(fm: FocusModel, windows: WindowList): Unit =
    fm.focused.foreach { id =>
      assert(windows.get(id).isDefined, s"focused window $id not in list")
      assert(windows.get(id).exists(_.mapped), s"focused window $id is not mapped")
    }

  test("Stability: add then remove leaves the list state equivalent for that id") {
    val id = WindowId(7L)
    val before = WindowList.empty
    val after = before.add(mkState(id)).remove(id)
    assertEquals(after.get(id), None)
    assert(!after.ordered.contains(id))
  }

  // ── ScalaCheck properties ───────────────────────────────────────────

  private given Arbitrary[WindowId] =
    Arbitrary(Gen.choose(0L, 1000L).map(WindowId(_)))

  private val genWindowState: Gen[WindowState] =
    for
      id     <- Arbitrary.arbitrary[WindowId]
      mapped <- Arbitrary.arbitrary[Boolean]
    yield WindowState(id, mapped, None, None, Rect(0, 0, 100, 100))

  private given Arbitrary[WindowState] = Arbitrary(genWindowState)

  property("WindowList: size equals ordered.length for any sequence of distinct adds") {
    forAll(Gen.listOf(genWindowState)) { states =>
      val wl = states.foldLeft(WindowList.empty)((acc, s) => acc.add(s))
      wl.size == wl.ordered.length
    }
  }

  property("WindowList.add is idempotent on the same id (replaces state, no duplicates)") {
    forAll { (w: WindowState) =>
      val wl = WindowList.empty.add(w).add(w)
      wl.size == 1 && wl.ordered == List(w.id) && wl.get(w.id) == Some(w)
    }
  }

  property("WindowList.remove then get returns None for that id") {
    forAll(Gen.listOf(genWindowState), Arbitrary.arbitrary[WindowId]) { (states, target) =>
      val wl = states.foldLeft(WindowList.empty)((acc, s) => acc.add(s)).remove(target)
      wl.get(target).isEmpty
    }
  }

  property("FocusModel: after focus(id), if focused is Some(id) then id is mapped in windows") {
    forAll(Gen.listOf(genWindowState).suchThat(_.nonEmpty)) { states =>
      val wl = states.foldLeft(WindowList.empty)((acc, s) => acc.add(s))
      states.forall { s =>
        val fm = FocusModel.empty.focus(s.id, wl)
        fm.focused.forall(id => wl.get(id).exists(_.mapped))
      }
    }
  }
