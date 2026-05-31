package compositor.ffi

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

// Additional opaque types
type WlrCursor = CStruct0
type WlrXcursorManager = CStruct0
type WlrXdgToplevel = CStruct0
type WlrXdgSurface = CStruct0
type WlrSurface = CStruct0
type WlrOutputMode = CStruct0
type WlrKeyboard = CStruct0
type WlrPointer = CStruct0
type WlrDataSource = CStruct0
type WlrPrimarySelectionSource = CStruct0
type WlrInputDevice = CStruct0
type WlrSceneNode = CStruct0
type WlrLayerShellV1 = CStruct0
type WlrLayerSurfaceV1 = CStruct0
type WlrSceneLayerSurfaceV1 = CStruct0
type WlrDrag = CStruct0
type WlrDragIcon = CStruct0
type WlrPointerConstraintsV1 = CStruct0
type WlrPointerConstraintV1 = CStruct0
type WlrRelativePointerManagerV1 = CStruct0
type WlrPointerGesturesV1 = CStruct0
type WlrBuffer = CStruct0
type WlrSceneBuffer = CStruct0

@extern
object Helpers:

  // ── Listener helpers ──────────────────────────────────────────────
  def helper_create_listener(notify: CFuncPtr2[Ptr[Byte], Ptr[Byte], Unit]): Ptr[Byte] = extern
  def helper_destroy_listener(listener: Ptr[Byte]): Unit = extern

  // ── Backend signals ───────────────────────────────────────────────
  def helper_backend_get_new_output(backend: Ptr[WlrBackend]): Ptr[Byte] = extern
  def helper_backend_get_new_input(backend: Ptr[WlrBackend]): Ptr[Byte] = extern
  @name("wlr_headless_backend_create")
  def helper_headless_backend_create(loop: Ptr[WlEventLoop]): Ptr[WlrBackend] = extern
  @name("wlr_headless_add_output")
  def helper_headless_add_output(backend: Ptr[WlrBackend], width: UInt, height: UInt): Ptr[WlrOutput] = extern

  // ── Output accessors ──────────────────────────────────────────────
  def helper_output_get_frame(output: Ptr[WlrOutput]): Ptr[Byte] = extern
  def helper_output_get_request_state(output: Ptr[WlrOutput]): Ptr[Byte] = extern
  def helper_output_get_destroy(output: Ptr[WlrOutput]): Ptr[Byte] = extern
  def helper_output_get_name(output: Ptr[WlrOutput]): CString = extern
  def helper_output_get_width(output: Ptr[WlrOutput]): CInt = extern
  def helper_output_get_height(output: Ptr[WlrOutput]): CInt = extern

  // ── Output init render ─────────────────────────────────────────────
  @name("wlr_output_init_render")
  def helper_output_init_render(output: Ptr[WlrOutput], allocator: Ptr[WlrAllocator], renderer: Ptr[WlrRenderer]): CInt = extern

  // ── Output state ──────────────────────────────────────────────────
  def helper_output_state_create(): Ptr[WlrOutputState] = extern
  def helper_output_state_destroy(state: Ptr[WlrOutputState]): Unit = extern
  @name("wlr_output_state_set_enabled")
  def helper_output_state_set_enabled(state: Ptr[WlrOutputState], enabled: CInt): Unit = extern
  @name("wlr_output_state_set_mode")
  def helper_output_state_set_mode(state: Ptr[WlrOutputState], mode: Ptr[WlrOutputMode]): Unit = extern
  @name("wlr_output_preferred_mode")
  def helper_output_get_preferred_mode(output: Ptr[WlrOutput]): Ptr[WlrOutputMode] = extern
  @name("wlr_output_commit_state")
  def helper_output_commit_state(output: Ptr[WlrOutput], state: Ptr[WlrOutputState]): CInt = extern
  def helper_output_get_gamma_size(output: Ptr[WlrOutput]): CInt = extern
  def helper_output_set_gamma_brightness(output: Ptr[WlrOutput], factor: CFloat): CInt = extern
  @name("wlr_output_test_state")
  def helper_output_test_state(output: Ptr[WlrOutput], state: Ptr[WlrOutputState]): CInt = extern
  def helper_output_event_request_state_get_state(event: Ptr[Byte]): Ptr[WlrOutputState] = extern

  // ── XDG Shell signals ─────────────────────────────────────────────
  def helper_xdg_shell_get_new_toplevel(shell: Ptr[WlrXdgShell]): Ptr[Byte] = extern

  // ── XDG Toplevel ──────────────────────────────────────────────────
  def helper_xdg_toplevel_get_map(toplevel: Ptr[WlrXdgToplevel]): Ptr[Byte] = extern
  def helper_xdg_toplevel_get_unmap(toplevel: Ptr[WlrXdgToplevel]): Ptr[Byte] = extern
  def helper_xdg_toplevel_get_destroy(toplevel: Ptr[WlrXdgToplevel]): Ptr[Byte] = extern
  def helper_xdg_toplevel_get_request_move(toplevel: Ptr[WlrXdgToplevel]): Ptr[Byte] = extern
  def helper_xdg_toplevel_get_request_resize(toplevel: Ptr[WlrXdgToplevel]): Ptr[Byte] = extern
  def helper_xdg_toplevel_get_request_maximize(toplevel: Ptr[WlrXdgToplevel]): Ptr[Byte] = extern
  def helper_xdg_toplevel_get_request_fullscreen(toplevel: Ptr[WlrXdgToplevel]): Ptr[Byte] = extern
  def helper_xdg_toplevel_get_title(toplevel: Ptr[WlrXdgToplevel]): CString = extern
  def helper_xdg_toplevel_get_app_id(toplevel: Ptr[WlrXdgToplevel]): CString = extern
  def helper_xdg_toplevel_get_surface(toplevel: Ptr[WlrXdgToplevel]): Ptr[WlrSurface] = extern
  def helper_xdg_toplevel_get_xdg_surface(toplevel: Ptr[WlrXdgToplevel]): Ptr[WlrXdgSurface] = extern
  @name("wlr_scene_xdg_surface_create")
  def helper_xdg_surface_create_scene_tree(parent: Ptr[WlrSceneTree], surface: Ptr[WlrXdgSurface]): Ptr[WlrSceneTree] = extern
  @name("wlr_xdg_toplevel_set_size")
  def helper_xdg_toplevel_set_size(toplevel: Ptr[WlrXdgToplevel], w: CInt, h: CInt): Unit = extern
  @name("wlr_xdg_toplevel_set_activated")
  def helper_xdg_toplevel_set_activated(toplevel: Ptr[WlrXdgToplevel], activated: CInt): Unit = extern

  // ── Interactive move/resize ──────────────────────────────────────
  def helper_xdg_toplevel_request_resize_get_edges(event: Ptr[Byte]): UInt = extern
  def helper_scene_node_get_x(node: Ptr[WlrSceneNode]): CInt = extern
  def helper_scene_node_get_y(node: Ptr[WlrSceneNode]): CInt = extern
  def helper_xdg_toplevel_get_width(toplevel: Ptr[WlrXdgToplevel]): CInt = extern
  def helper_xdg_toplevel_get_height(toplevel: Ptr[WlrXdgToplevel]): CInt = extern

  // ── Scene ─────────────────────────────────────────────────────────
  def helper_scene_get_tree(scene: Ptr[WlrScene]): Ptr[WlrSceneTree] = extern
  @name("wlr_scene_get_scene_output")
  def helper_scene_get_output(scene: Ptr[WlrScene], output: Ptr[WlrOutput]): Ptr[WlrSceneOutput] = extern
  def helper_scene_output_commit(sceneOutput: Ptr[WlrSceneOutput], options: Ptr[Byte]): CInt = extern
  def helper_scene_output_send_frame_done(sceneOutput: Ptr[WlrSceneOutput]): Unit = extern
  def helper_scene_node_at(scene: Ptr[WlrScene], lx: CDouble, ly: CDouble, nx: Ptr[CDouble], ny: Ptr[CDouble]): Ptr[WlrSceneNode] = extern
  @name("wlr_scene_node_set_position")
  def helper_scene_node_set_position(node: Ptr[WlrSceneNode], x: CInt, y: CInt): Unit = extern
  @name("wlr_scene_node_raise_to_top")
  def helper_scene_node_raise_to_top(node: Ptr[WlrSceneNode]): Unit = extern
  @name("wlr_scene_node_set_enabled")
  def helper_scene_node_set_enabled(node: Ptr[WlrSceneNode], enabled: CInt): Unit = extern
  def helper_scene_node_get_surface(node: Ptr[WlrSceneNode]): Ptr[WlrSurface] = extern
  def helper_scene_node_get_toplevel_data(node: Ptr[WlrSceneNode]): Ptr[Byte] = extern
  def helper_scene_tree_set_data(tree: Ptr[WlrSceneTree], data: Ptr[Byte]): Unit = extern
  @name("wlr_scene_output_create")
  def helper_scene_output_create(scene: Ptr[WlrScene], output: Ptr[WlrOutput]): Ptr[WlrSceneOutput] = extern

  // ── Input device ──────────────────────────────────────────────────
  def helper_input_device_get_type(dev: Ptr[WlrInputDevice]): CInt = extern
  @name("wlr_keyboard_from_input_device")
  def helper_keyboard_from_input_device(dev: Ptr[WlrInputDevice]): Ptr[WlrKeyboard] = extern
  @name("wlr_pointer_from_input_device")
  def helper_pointer_from_input_device(dev: Ptr[WlrInputDevice]): Ptr[WlrPointer] = extern

  // ── Touchpad / libinput configuration ─────────────────────────
  def helper_configure_touchpad(dev: Ptr[WlrInputDevice]): Unit = extern
  def helper_input_device_is_touchpad(dev: Ptr[WlrInputDevice]): CInt = extern

  // ── Keyboard ──────────────────────────────────────────────────────
  def helper_keyboard_get_key(kb: Ptr[WlrKeyboard]): Ptr[Byte] = extern
  def helper_keyboard_get_modifiers(kb: Ptr[WlrKeyboard]): Ptr[Byte] = extern
  def helper_keyboard_set_keymap(kb: Ptr[WlrKeyboard]): Unit = extern
  @name("wlr_keyboard_set_repeat_info")
  def helper_keyboard_set_repeat_info(kb: Ptr[WlrKeyboard], rate: CInt, delay: CInt): Unit = extern
  def helper_key_event_get_keycode(event: Ptr[Byte]): UInt = extern
  def helper_key_event_get_state(event: Ptr[Byte]): CInt = extern
  def helper_key_event_get_time_msec(event: Ptr[Byte]): UInt = extern

  // ── Seat ──────────────────────────────────────────────────────────
  @name("wlr_seat_set_keyboard")
  def helper_seat_set_keyboard(seat: Ptr[WlrSeat], kb: Ptr[WlrKeyboard]): Unit = extern
  @name("wlr_seat_keyboard_notify_key")
  def helper_seat_keyboard_notify_key(seat: Ptr[WlrSeat], time: UInt, key: UInt, state: UInt): Unit = extern
  def helper_seat_keyboard_notify_modifiers(seat: Ptr[WlrSeat], kb: Ptr[WlrKeyboard]): Unit = extern // accesses kb->modifiers
  def helper_seat_keyboard_notify_enter(seat: Ptr[WlrSeat], surface: Ptr[WlrSurface], kb: Ptr[WlrKeyboard]): Unit = extern // accesses kb fields
  @name("wlr_seat_pointer_notify_enter")
  def helper_seat_pointer_notify_enter(seat: Ptr[WlrSeat], surface: Ptr[WlrSurface], sx: CDouble, sy: CDouble): Unit = extern
  @name("wlr_seat_pointer_notify_motion")
  def helper_seat_pointer_notify_motion(seat: Ptr[WlrSeat], time: UInt, sx: CDouble, sy: CDouble): Unit = extern
  @name("wlr_seat_pointer_notify_button")
  def helper_seat_pointer_notify_button(seat: Ptr[WlrSeat], time: UInt, button: UInt, state: UInt): Unit = extern
  @name("wlr_seat_pointer_notify_axis")
  def helper_seat_pointer_notify_axis(seat: Ptr[WlrSeat], time: UInt, orientation: UInt, value: CDouble, value_discrete: CInt, source: UInt, relative_direction: UInt): Unit = extern
  @name("wlr_seat_pointer_notify_frame")
  def helper_seat_pointer_notify_frame(seat: Ptr[WlrSeat]): Unit = extern
  @name("wlr_seat_pointer_clear_focus")
  def helper_seat_pointer_clear_focus(seat: Ptr[WlrSeat]): Unit = extern
  @name("wlr_seat_keyboard_clear_focus")
  def helper_seat_keyboard_clear_focus(seat: Ptr[WlrSeat]): Unit = extern
  def helper_seat_get_request_set_cursor(seat: Ptr[WlrSeat]): Ptr[Byte] = extern

  // ── Seat selection (clipboard) ────────────────────────────────
  def helper_seat_get_request_set_selection(seat: Ptr[WlrSeat]): Ptr[Byte] = extern
  def helper_seat_get_request_set_primary_selection(seat: Ptr[WlrSeat]): Ptr[Byte] = extern
  def helper_selection_event_get_source(event: Ptr[Byte]): Ptr[WlrDataSource] = extern
  def helper_selection_event_get_serial(event: Ptr[Byte]): UInt = extern
  def helper_primary_selection_event_get_source(event: Ptr[Byte]): Ptr[WlrPrimarySelectionSource] = extern
  def helper_primary_selection_event_get_serial(event: Ptr[Byte]): UInt = extern
  @name("wlr_seat_set_selection")
  def helper_seat_set_selection(seat: Ptr[WlrSeat], source: Ptr[WlrDataSource], serial: UInt): Unit = extern
  @name("wlr_seat_set_primary_selection")
  def helper_seat_set_primary_selection(seat: Ptr[WlrSeat], source: Ptr[WlrPrimarySelectionSource], serial: UInt): Unit = extern

  // ── Cursor ────────────────────────────────────────────────────────
  @name("wlr_cursor_create")
  def helper_cursor_create(): Ptr[WlrCursor] = extern
  @name("wlr_cursor_attach_output_layout")
  def helper_cursor_attach_output_layout(cursor: Ptr[WlrCursor], layout: Ptr[WlrOutputLayout]): Unit = extern
  @name("wlr_cursor_attach_input_device")
  def helper_cursor_attach_input_device(cursor: Ptr[WlrCursor], device: Ptr[WlrInputDevice]): Unit = extern
  def helper_cursor_get_motion(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern // accesses cursor->events.motion
  def helper_cursor_get_motion_absolute(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_button(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_axis(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_frame(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_x(cursor: Ptr[WlrCursor]): CDouble = extern // accesses cursor->x
  def helper_cursor_get_y(cursor: Ptr[WlrCursor]): CDouble = extern
  @name("wlr_cursor_move")
  def helper_cursor_move(cursor: Ptr[WlrCursor], dev: Ptr[WlrInputDevice], dx: CDouble, dy: CDouble): Unit = extern
  @name("wlr_cursor_warp_absolute")
  def helper_cursor_warp_absolute(cursor: Ptr[WlrCursor], dev: Ptr[WlrInputDevice], x: CDouble, y: CDouble): Unit = extern
  @name("wlr_cursor_warp_closest")
  def helper_cursor_warp_closest(cursor: Ptr[WlrCursor], dev: Ptr[WlrInputDevice], x: CDouble, y: CDouble): Unit = extern
  @name("wlr_cursor_set_surface")
  def helper_cursor_set_surface(cursor: Ptr[WlrCursor], surface: Ptr[WlrSurface], hx: CInt, hy: CInt): Unit = extern
  @name("wlr_cursor_absolute_to_layout_coords")
  def helper_cursor_absolute_to_layout_coords(cursor: Ptr[WlrCursor], dev: Ptr[WlrInputDevice], x: CDouble, y: CDouble, lx: Ptr[CDouble], ly: Ptr[CDouble]): Unit = extern
  @name("wlr_xcursor_manager_create")
  def helper_xcursor_manager_create(theme: CString, size: CInt): Ptr[WlrXcursorManager] = extern
  @name("wlr_xcursor_manager_load")
  def helper_xcursor_manager_load(mgr: Ptr[WlrXcursorManager], scale: CFloat): Unit = extern
  def helper_xcursor_manager_set_cursor_image(mgr: Ptr[WlrXcursorManager], name: CString, cursor: Ptr[WlrCursor]): Unit = extern // reorders args

  // ── Pointer events ────────────────────────────────────────────────
  def helper_pointer_motion_get_dx(event: Ptr[Byte]): CDouble = extern
  def helper_pointer_motion_get_dy(event: Ptr[Byte]): CDouble = extern
  def helper_pointer_motion_get_time(event: Ptr[Byte]): UInt = extern
  def helper_pointer_motion_get_device(event: Ptr[Byte]): Ptr[WlrInputDevice] = extern
  def helper_pointer_motion_absolute_get_x(event: Ptr[Byte]): CDouble = extern
  def helper_pointer_motion_absolute_get_y(event: Ptr[Byte]): CDouble = extern
  def helper_pointer_motion_absolute_get_time(event: Ptr[Byte]): UInt = extern
  def helper_pointer_motion_absolute_get_device(event: Ptr[Byte]): Ptr[WlrInputDevice] = extern
  def helper_pointer_button_get_button(event: Ptr[Byte]): UInt = extern
  def helper_pointer_button_get_state(event: Ptr[Byte]): UInt = extern
  def helper_pointer_button_get_time(event: Ptr[Byte]): UInt = extern
  // Pointer axis (scroll) event accessors
  def helper_pointer_axis_get_time(event: Ptr[Byte]): UInt = extern
  def helper_pointer_axis_get_orientation(event: Ptr[Byte]): UInt = extern
  def helper_pointer_axis_get_delta(event: Ptr[Byte]): CDouble = extern
  def helper_pointer_axis_get_delta_discrete(event: Ptr[Byte]): CInt = extern
  def helper_pointer_axis_get_source(event: Ptr[Byte]): UInt = extern
  def helper_pointer_axis_get_relative_direction(event: Ptr[Byte]): UInt = extern
  def helper_set_cursor_event_get_surface(event: Ptr[Byte]): Ptr[WlrSurface] = extern
  def helper_set_cursor_event_get_hotspot_x(event: Ptr[Byte]): CInt = extern
  def helper_set_cursor_event_get_hotspot_y(event: Ptr[Byte]): CInt = extern

  // ── Output layout ─────────────────────────────────────────────────
  @name("wlr_output_layout_add_auto")
  def helper_output_layout_add_auto(layout: Ptr[WlrOutputLayout], output: Ptr[WlrOutput]): Ptr[Byte] = extern
  def helper_output_layout_get_x(layout: Ptr[WlrOutputLayout], output: Ptr[WlrOutput]): CInt = extern // accesses layout result
  def helper_output_layout_get_y(layout: Ptr[WlrOutputLayout], output: Ptr[WlrOutput]): CInt = extern
  @name("wlr_output_layout_output_at")
  def helper_output_layout_output_at(layout: Ptr[WlrOutputLayout], lx: CDouble, ly: CDouble): Ptr[WlrOutput] = extern

  // ── Scene rect (background) ───────────────────────────────────────
  def helper_scene_rect_create(parent: Ptr[WlrSceneTree], w: CInt, h: CInt, r: CFloat, g: CFloat, b: CFloat, a: CFloat): Ptr[Byte] = extern // packs color array
  @name("wlr_scene_rect_set_size")
  def helper_scene_rect_set_size(rect: Ptr[Byte], w: CInt, h: CInt): Unit = extern

  // ── XKB keysym ────────────────────────────────────────────────────
  def helper_keyboard_get_keysyms(kb: Ptr[WlrKeyboard], keycode: UInt, syms: Ptr[Ptr[UInt]]): CInt = extern

  // ── Layer shell ──────────────────────────────────────────────────
  def helper_layer_shell_create(display: Ptr[WlDisplay]): Ptr[WlrLayerShellV1] = extern
  def helper_layer_shell_get_new_surface(shell: Ptr[WlrLayerShellV1]): Ptr[Byte] = extern
  def helper_layer_surface_get_namespace(ls: Ptr[WlrLayerSurfaceV1]): CString = extern
  def helper_layer_surface_get_layer(ls: Ptr[WlrLayerSurfaceV1]): UInt = extern
  def helper_layer_surface_get_pending_layer(ls: Ptr[WlrLayerSurfaceV1]): UInt = extern
  def helper_layer_surface_get_anchor(ls: Ptr[WlrLayerSurfaceV1]): UInt = extern
  def helper_layer_surface_get_exclusive_zone(ls: Ptr[WlrLayerSurfaceV1]): CInt = extern
  def helper_layer_surface_get_keyboard_interactive(ls: Ptr[WlrLayerSurfaceV1]): UInt = extern
  def helper_layer_surface_get_output(ls: Ptr[WlrLayerSurfaceV1]): Ptr[WlrOutput] = extern
  def helper_layer_surface_set_output(ls: Ptr[WlrLayerSurfaceV1], output: Ptr[WlrOutput]): Unit = extern
  def helper_layer_surface_get_destroy(ls: Ptr[WlrLayerSurfaceV1]): Ptr[Byte] = extern
  def helper_layer_surface_get_surface_commit(ls: Ptr[WlrLayerSurfaceV1]): Ptr[Byte] = extern
  @name("wlr_layer_surface_v1_destroy")
  def helper_layer_surface_close(ls: Ptr[WlrLayerSurfaceV1]): Unit = extern

  // ── Damage tracking ───────────────────────────────────────────────
  @name("wlr_scene_output_build_state")
  def helper_scene_output_build_state(sceneOutput: Ptr[WlrSceneOutput], state: Ptr[WlrOutputState], options: Ptr[Byte]): Boolean = extern
  def helper_output_state_extract_damage(state: Ptr[WlrOutputState], buf: Ptr[CInt], maxRects: CInt): CInt = extern

  // ── Scene layer surface ──────────────────────────────────────────
  @name("wlr_scene_tree_create")
  def helper_scene_tree_create(parent: Ptr[WlrSceneTree]): Ptr[WlrSceneTree] = extern
  @name("wlr_scene_layer_surface_v1_create")
  def helper_scene_layer_surface_create(parent: Ptr[WlrSceneTree], ls: Ptr[WlrLayerSurfaceV1]): Ptr[WlrSceneLayerSurfaceV1] = extern
  def helper_scene_layer_surface_configure(sls: Ptr[WlrSceneLayerSurfaceV1], fullX: CInt, fullY: CInt, fullW: CInt, fullH: CInt, usableX: Ptr[CInt], usableY: Ptr[CInt], usableW: Ptr[CInt], usableH: Ptr[CInt]): Unit = extern // constructs wlr_box
  def helper_scene_layer_surface_get_tree(sls: Ptr[WlrSceneLayerSurfaceV1]): Ptr[WlrSceneTree] = extern // accesses sls->tree
  @name("wlr_scene_node_reparent")
  def helper_scene_node_reparent(node: Ptr[WlrSceneNode], parent: Ptr[WlrSceneTree]): Unit = extern

  // ── Drag-and-drop ─────────────────────────────────────────────
  def helper_seat_get_start_drag(seat: Ptr[WlrSeat]): Ptr[Byte] = extern
  def helper_drag_get_icon(drag: Ptr[WlrDrag]): Ptr[WlrDragIcon] = extern
  def helper_drag_get_focus_surface(drag: Ptr[WlrDrag]): Ptr[WlrSurface] = extern
  def helper_drag_get_is_pointer_drag(drag: Ptr[WlrDrag]): CInt = extern
  def helper_drag_icon_get_surface(icon: Ptr[WlrDragIcon]): Ptr[WlrSurface] = extern
  def helper_drag_icon_get_destroy(icon: Ptr[WlrDragIcon]): Ptr[Byte] = extern
  @name("wlr_scene_drag_icon_create")
  def helper_scene_drag_icon_create(parent: Ptr[WlrSceneTree], icon: Ptr[WlrDragIcon]): Ptr[WlrSceneTree] = extern

  // ── Pointer constraints ───────────────────────────────────────
  @name("wlr_pointer_constraints_v1_create")
  def helper_pointer_constraints_create(display: Ptr[WlDisplay]): Ptr[WlrPointerConstraintsV1] = extern
  def helper_pointer_constraints_get_new_constraint(pc: Ptr[WlrPointerConstraintsV1]): Ptr[Byte] = extern // accesses pc->events
  def helper_pointer_constraint_get_surface(constraint: Ptr[WlrPointerConstraintV1]): Ptr[WlrSurface] = extern // accesses constraint->surface
  def helper_pointer_constraint_get_type(constraint: Ptr[WlrPointerConstraintV1]): UInt = extern
  def helper_pointer_constraint_get_set_region(constraint: Ptr[WlrPointerConstraintV1]): Ptr[Byte] = extern
  def helper_pointer_constraint_get_destroy(constraint: Ptr[WlrPointerConstraintV1]): Ptr[Byte] = extern
  @name("wlr_pointer_constraint_v1_send_activated")
  def helper_pointer_constraint_send_activated(constraint: Ptr[WlrPointerConstraintV1]): Unit = extern
  @name("wlr_pointer_constraint_v1_send_deactivated")
  def helper_pointer_constraint_send_deactivated(constraint: Ptr[WlrPointerConstraintV1]): Unit = extern
  @name("wlr_pointer_constraints_v1_constraint_for_surface")
  def helper_pointer_constraints_constraint_for_surface(pc: Ptr[WlrPointerConstraintsV1], surface: Ptr[WlrSurface], seat: Ptr[WlrSeat]): Ptr[WlrPointerConstraintV1] = extern

  // ── Relative pointer ──────────────────────────────────────────
  @name("wlr_relative_pointer_manager_v1_create")
  def helper_relative_pointer_manager_create(display: Ptr[WlDisplay]): Ptr[WlrRelativePointerManagerV1] = extern
  @name("wlr_relative_pointer_manager_v1_send_relative_motion")
  def helper_relative_pointer_send_relative_motion(mgr: Ptr[WlrRelativePointerManagerV1], seat: Ptr[WlrSeat], time_usec: ULong, dx: CDouble, dy: CDouble, dx_unaccel: CDouble, dy_unaccel: CDouble): Unit = extern
  def helper_pointer_motion_get_unaccel_dx(event: Ptr[Byte]): CDouble = extern
  def helper_pointer_motion_get_unaccel_dy(event: Ptr[Byte]): CDouble = extern

  // ── Pointer gestures ──────────────────────────────────────────
  @name("wlr_pointer_gestures_v1_create")
  def helper_pointer_gestures_create(display: Ptr[WlDisplay]): Ptr[WlrPointerGesturesV1] = extern
  def helper_cursor_get_swipe_begin(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_swipe_update(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_swipe_end(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_pinch_begin(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_pinch_update(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_pinch_end(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_hold_begin(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_cursor_get_hold_end(cursor: Ptr[WlrCursor]): Ptr[Byte] = extern
  def helper_swipe_begin_get_time(event: Ptr[Byte]): UInt = extern
  def helper_swipe_begin_get_fingers(event: Ptr[Byte]): UInt = extern
  def helper_swipe_update_get_time(event: Ptr[Byte]): UInt = extern
  def helper_swipe_update_get_fingers(event: Ptr[Byte]): UInt = extern
  def helper_swipe_update_get_dx(event: Ptr[Byte]): CDouble = extern
  def helper_swipe_update_get_dy(event: Ptr[Byte]): CDouble = extern
  def helper_swipe_end_get_time(event: Ptr[Byte]): UInt = extern
  def helper_swipe_end_get_cancelled(event: Ptr[Byte]): CInt = extern
  def helper_pinch_begin_get_time(event: Ptr[Byte]): UInt = extern
  def helper_pinch_begin_get_fingers(event: Ptr[Byte]): UInt = extern
  def helper_pinch_update_get_time(event: Ptr[Byte]): UInt = extern
  def helper_pinch_update_get_fingers(event: Ptr[Byte]): UInt = extern
  def helper_pinch_update_get_dx(event: Ptr[Byte]): CDouble = extern
  def helper_pinch_update_get_dy(event: Ptr[Byte]): CDouble = extern
  def helper_pinch_update_get_scale(event: Ptr[Byte]): CDouble = extern
  def helper_pinch_update_get_rotation(event: Ptr[Byte]): CDouble = extern
  def helper_pinch_end_get_time(event: Ptr[Byte]): UInt = extern
  def helper_pinch_end_get_cancelled(event: Ptr[Byte]): CInt = extern
  def helper_hold_begin_get_time(event: Ptr[Byte]): UInt = extern
  def helper_hold_begin_get_fingers(event: Ptr[Byte]): UInt = extern
  def helper_hold_end_get_time(event: Ptr[Byte]): UInt = extern
  def helper_hold_end_get_cancelled(event: Ptr[Byte]): CInt = extern
  @name("wlr_pointer_gestures_v1_send_swipe_begin")
  def helper_pointer_gestures_send_swipe_begin(gestures: Ptr[WlrPointerGesturesV1], seat: Ptr[WlrSeat], time: UInt, fingers: UInt): Unit = extern
  @name("wlr_pointer_gestures_v1_send_swipe_update")
  def helper_pointer_gestures_send_swipe_update(gestures: Ptr[WlrPointerGesturesV1], seat: Ptr[WlrSeat], time: UInt, dx: CDouble, dy: CDouble): Unit = extern
  @name("wlr_pointer_gestures_v1_send_swipe_end")
  def helper_pointer_gestures_send_swipe_end(gestures: Ptr[WlrPointerGesturesV1], seat: Ptr[WlrSeat], time: UInt, cancelled: CInt): Unit = extern
  @name("wlr_pointer_gestures_v1_send_pinch_begin")
  def helper_pointer_gestures_send_pinch_begin(gestures: Ptr[WlrPointerGesturesV1], seat: Ptr[WlrSeat], time: UInt, fingers: UInt): Unit = extern
  @name("wlr_pointer_gestures_v1_send_pinch_update")
  def helper_pointer_gestures_send_pinch_update(gestures: Ptr[WlrPointerGesturesV1], seat: Ptr[WlrSeat], time: UInt, dx: CDouble, dy: CDouble, scale: CDouble, rotation: CDouble): Unit = extern
  @name("wlr_pointer_gestures_v1_send_pinch_end")
  def helper_pointer_gestures_send_pinch_end(gestures: Ptr[WlrPointerGesturesV1], seat: Ptr[WlrSeat], time: UInt, cancelled: CInt): Unit = extern
  @name("wlr_pointer_gestures_v1_send_hold_begin")
  def helper_pointer_gestures_send_hold_begin(gestures: Ptr[WlrPointerGesturesV1], seat: Ptr[WlrSeat], time: UInt, fingers: UInt): Unit = extern
  @name("wlr_pointer_gestures_v1_send_hold_end")
  def helper_pointer_gestures_send_hold_end(gestures: Ptr[WlrPointerGesturesV1], seat: Ptr[WlrSeat], time: UInt, cancelled: CInt): Unit = extern

  // ── Launcher widget ─────────────────────────────────────────────
  def helper_launcher_buffer_create(width: CInt, height: CInt): Ptr[WlrBuffer] = extern
  def helper_launcher_buffer_get_data(buf: Ptr[WlrBuffer]): Ptr[Byte] = extern
  def helper_launcher_render(buf: Ptr[WlrBuffer], text: CString, textLen: CInt, w: CInt, h: CInt): Unit = extern
  @name("wlr_scene_buffer_create")
  def helper_scene_buffer_create(parent: Ptr[WlrSceneTree], buf: Ptr[WlrBuffer]): Ptr[WlrSceneBuffer] = extern
  @name("wlr_scene_buffer_set_buffer")
  def helper_scene_buffer_set_buffer(sceneBuf: Ptr[WlrSceneBuffer], buf: Ptr[WlrBuffer]): Unit = extern
  @name("wlr_scene_node_destroy")
  def helper_scene_node_destroy(node: Ptr[WlrSceneNode]): Unit = extern
  @name("xkb_keysym_to_utf32")
  def helper_keysym_to_utf32(keysym: UInt): UInt = extern
