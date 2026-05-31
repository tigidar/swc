#define _POSIX_C_SOURCE 199309L
#define WLR_USE_UNSTABLE
#include <wayland-server-core.h>
#include <wlr/backend.h>
#include <wlr/backend/headless.h>
#include <wlr/backend/libinput.h>
#include <libinput.h>
#include <wlr/render/allocator.h>
#include <wlr/render/wlr_renderer.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/types/wlr_cursor.h>
#include <wlr/types/wlr_input_device.h>
#include <wlr/types/wlr_keyboard.h>
#include <wlr/types/wlr_output.h>
#include <wlr/types/wlr_output_layout.h>
#include <wlr/types/wlr_pointer.h>
#include <wlr/types/wlr_scene.h>
#include <wlr/types/wlr_seat.h>
#include <wlr/types/wlr_data_device.h>
#include <wlr/types/wlr_primary_selection.h>
#include <wlr/types/wlr_subcompositor.h>
#include <wlr/types/wlr_xcursor_manager.h>
#include <wlr/types/wlr_xdg_shell.h>
#include <wlr/types/wlr_layer_shell_v1.h>
#include <wlr/types/wlr_pointer_constraints_v1.h>
#include <wlr/types/wlr_relative_pointer_v1.h>
#include <wlr/types/wlr_pointer_gestures_v1.h>
#include <xkbcommon/xkbcommon.h>
#include <stdlib.h>
#include <time.h>
#include <pixman.h>

/* ── Listener helpers ───────────────────────────────────────────────── */

struct wl_listener *helper_create_listener(wl_notify_func_t notify) {
    struct wl_listener *l = calloc(1, sizeof(struct wl_listener));
    l->notify = notify;
    return l;
}

void helper_destroy_listener(struct wl_listener *listener) {
    wl_list_remove(&listener->link);
    free(listener);
}

void helper_signal_add(struct wl_signal *signal, struct wl_listener *listener) {
    wl_signal_add(signal, listener);
}

/* ── Backend signal accessors ───────────────────────────────────────── */

struct wl_signal *helper_backend_get_new_output(struct wlr_backend *backend) {
    return &backend->events.new_output;
}

struct wl_signal *helper_backend_get_new_input(struct wlr_backend *backend) {
    return &backend->events.new_input;
}

/* ── Output accessors ───────────────────────────────────────────────── */

struct wl_signal *helper_output_get_frame(struct wlr_output *output) {
    return &output->events.frame;
}

struct wl_signal *helper_output_get_request_state(struct wlr_output *output) {
    return &output->events.request_state;
}

struct wl_signal *helper_output_get_destroy(struct wlr_output *output) {
    return &output->events.destroy;
}

const char *helper_output_get_name(struct wlr_output *output) {
    return output->name;
}

int helper_output_get_width(struct wlr_output *output) {
    return output->width;
}

int helper_output_get_height(struct wlr_output *output) {
    return output->height;
}

/* ── Output state helpers ───────────────────────────────────────────── */

struct wlr_output_state *helper_output_state_create(void) {
    struct wlr_output_state *state = calloc(1, sizeof(struct wlr_output_state));
    wlr_output_state_init(state);
    return state;
}

void helper_output_state_destroy(struct wlr_output_state *state) {
    wlr_output_state_finish(state);
    free(state);
}

/* Get the gamma LUT size for an output (typically 256 or 4096) */
int helper_output_get_gamma_size(struct wlr_output *output) {
    return wlr_output_get_gamma_size(output);
}

/* Apply a uniform gamma brightness factor (0.0-1.0) to an output.
 * Builds a linear ramp scaled by the factor and commits it. */
int helper_output_set_gamma_brightness(struct wlr_output *output, float factor) {
    size_t size = wlr_output_get_gamma_size(output);
    if (size == 0) return 0;

    uint16_t *r = calloc(size * 3, sizeof(uint16_t));
    if (!r) return 0;
    uint16_t *g = r + size;
    uint16_t *b = g + size;

    for (size_t i = 0; i < size; i++) {
        uint16_t val = (uint16_t)((float)(i * 65535) / (float)(size - 1) * factor);
        r[i] = val;
        g[i] = val;
        b[i] = val;
    }

    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_gamma_lut(&state, size, r, g, b);
    int ok = wlr_output_commit_state(output, &state);
    wlr_output_state_finish(&state);
    free(r);
    return ok;
}

/* ── Output event request_state accessor ────────────────────────────── */

struct wlr_output_state *helper_output_event_request_state_get_state(void *event) {
    struct wlr_output_event_request_state *e = event;
    return e->state;
}

/* ── XDG Shell signal accessors ─────────────────────────────────────── */

struct wl_signal *helper_xdg_shell_get_new_toplevel(struct wlr_xdg_shell *shell) {
    return &shell->events.new_toplevel;
}

/* ── XDG Toplevel accessors ─────────────────────────────────────────── */

struct wl_signal *helper_xdg_toplevel_get_map(struct wlr_xdg_toplevel *toplevel) {
    return &toplevel->base->surface->events.map;
}

struct wl_signal *helper_xdg_toplevel_get_unmap(struct wlr_xdg_toplevel *toplevel) {
    return &toplevel->base->surface->events.unmap;
}

struct wl_signal *helper_xdg_toplevel_get_destroy(struct wlr_xdg_toplevel *toplevel) {
    return &toplevel->events.destroy;
}

struct wl_signal *helper_xdg_toplevel_get_request_move(struct wlr_xdg_toplevel *toplevel) {
    return &toplevel->events.request_move;
}

struct wl_signal *helper_xdg_toplevel_get_request_resize(struct wlr_xdg_toplevel *toplevel) {
    return &toplevel->events.request_resize;
}

struct wl_signal *helper_xdg_toplevel_get_request_maximize(struct wlr_xdg_toplevel *toplevel) {
    return &toplevel->events.request_maximize;
}

struct wl_signal *helper_xdg_toplevel_get_request_fullscreen(struct wlr_xdg_toplevel *toplevel) {
    return &toplevel->events.request_fullscreen;
}

const char *helper_xdg_toplevel_get_title(struct wlr_xdg_toplevel *toplevel) {
    return toplevel->title;
}

const char *helper_xdg_toplevel_get_app_id(struct wlr_xdg_toplevel *toplevel) {
    return toplevel->app_id;
}

struct wlr_surface *helper_xdg_toplevel_get_surface(struct wlr_xdg_toplevel *toplevel) {
    return toplevel->base->surface;
}

struct wlr_xdg_surface *helper_xdg_toplevel_get_xdg_surface(struct wlr_xdg_toplevel *toplevel) {
    return toplevel->base;
}

/* ── Scene accessors ────────────────────────────────────────────────── */

struct wlr_scene_tree *helper_scene_get_tree(struct wlr_scene *scene) {
    return &scene->tree;
}

int helper_scene_output_commit(struct wlr_scene_output *scene_output, void *options) {
    return wlr_scene_output_commit(scene_output, NULL);
}

void helper_scene_output_send_frame_done(struct wlr_scene_output *scene_output) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    wlr_scene_output_send_frame_done(scene_output, &now);
}

struct wlr_scene_node *helper_scene_node_at(struct wlr_scene *scene,
                                              double lx, double ly,
                                              double *nx, double *ny) {
    return wlr_scene_node_at(&scene->tree.node, lx, ly, nx, ny);
}

/* Try to get surface + coordinates from a scene node (for input routing) */
struct wlr_surface *helper_scene_node_get_surface(struct wlr_scene_node *node) {
    if (node->type == WLR_SCENE_NODE_BUFFER) {
        struct wlr_scene_buffer *scene_buffer = wlr_scene_buffer_from_node(node);
        struct wlr_scene_surface *scene_surface =
            wlr_scene_surface_try_from_buffer(scene_buffer);
        if (scene_surface) {
            return scene_surface->surface;
        }
    }
    return NULL;
}

/* Walk up the scene tree to find the toplevel scene_tree that has data set */
void *helper_scene_node_get_toplevel_data(struct wlr_scene_node *node) {
    struct wlr_scene_tree *tree = node->parent;
    while (tree != NULL) {
        if (tree->node.data != NULL) {
            return tree->node.data;
        }
        tree = tree->node.parent;
    }
    return NULL;
}

void helper_scene_tree_set_data(struct wlr_scene_tree *tree, void *data) {
    tree->node.data = data;
}

/* ── Input device accessors ─────────────────────────────────────────── */

enum wlr_input_device_type helper_input_device_get_type(struct wlr_input_device *dev) {
    return dev->type;
}

/* ── Touchpad / libinput configuration ─────────────────────────── */

void helper_configure_touchpad(struct wlr_input_device *dev) {
    if (!wlr_input_device_is_libinput(dev)) return;
    struct libinput_device *libinput_dev = wlr_libinput_get_device_handle(dev);

    /* Tap-to-click: 1-finger=left, 2-finger=right, 3-finger=middle */
    if (libinput_device_config_tap_get_finger_count(libinput_dev) > 0) {
        libinput_device_config_tap_set_enabled(libinput_dev,
            LIBINPUT_CONFIG_TAP_ENABLED);
        libinput_device_config_tap_set_button_map(libinput_dev,
            LIBINPUT_CONFIG_TAP_MAP_LRM);
        /* Tap-and-drag */
        libinput_device_config_tap_set_drag_enabled(libinput_dev,
            LIBINPUT_CONFIG_DRAG_ENABLED);
        libinput_device_config_tap_set_drag_lock_enabled(libinput_dev,
            LIBINPUT_CONFIG_DRAG_LOCK_DISABLED);
    }

    /* Natural scrolling */
    if (libinput_device_config_scroll_has_natural_scroll(libinput_dev)) {
        libinput_device_config_scroll_set_natural_scroll_enabled(libinput_dev, 1);
    }

    /* Disable-while-typing */
    if (libinput_device_config_dwt_is_available(libinput_dev)) {
        libinput_device_config_dwt_set_enabled(libinput_dev,
            LIBINPUT_CONFIG_DWT_ENABLED);
    }

    /* Acceleration: adaptive profile */
    libinput_device_config_accel_set_profile(libinput_dev,
        LIBINPUT_CONFIG_ACCEL_PROFILE_ADAPTIVE);
}

int helper_input_device_is_touchpad(struct wlr_input_device *dev) {
    if (!wlr_input_device_is_libinput(dev)) return 0;
    struct libinput_device *libinput_dev = wlr_libinput_get_device_handle(dev);
    return libinput_device_config_tap_get_finger_count(libinput_dev) > 0 ? 1 : 0;
}

/* ── Keyboard accessors ─────────────────────────────────────────────── */

struct wl_signal *helper_keyboard_get_key(struct wlr_keyboard *kb) {
    return &kb->events.key;
}

struct wl_signal *helper_keyboard_get_modifiers(struct wlr_keyboard *kb) {
    return &kb->events.modifiers;
}

void helper_keyboard_set_keymap(struct wlr_keyboard *kb) {
    struct xkb_context *ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    struct xkb_keymap *keymap = xkb_keymap_new_from_names(ctx, NULL,
        XKB_KEYMAP_COMPILE_NO_FLAGS);
    wlr_keyboard_set_keymap(kb, keymap);
    xkb_keymap_unref(keymap);
    xkb_context_unref(ctx);
}

uint32_t *helper_keyboard_get_keycodes(struct wlr_keyboard *kb) {
    return kb->keycodes;
}

uint32_t helper_keyboard_get_num_keycodes(struct wlr_keyboard *kb) {
    return kb->num_keycodes;
}

uint32_t helper_keyboard_get_modifier_mask(struct wlr_keyboard *kb) {
    return wlr_keyboard_get_modifiers(kb);
}

/* Key event accessors */
uint32_t helper_key_event_get_keycode(void *event) {
    struct wlr_keyboard_key_event *e = event;
    return e->keycode;
}

int helper_key_event_get_state(void *event) {
    struct wlr_keyboard_key_event *e = event;
    return e->state;  /* WL_KEYBOARD_KEY_STATE_RELEASED=0, PRESSED=1 */
}

uint32_t helper_key_event_get_time_msec(void *event) {
    struct wlr_keyboard_key_event *e = event;
    return e->time_msec;
}

/* ── Seat helpers ───────────────────────────────────────────────────── */

void helper_seat_keyboard_notify_modifiers(struct wlr_seat *seat,
                                            struct wlr_keyboard *kb) {
    wlr_seat_keyboard_notify_modifiers(seat, &kb->modifiers);
}

void helper_seat_keyboard_notify_enter(struct wlr_seat *seat,
                                        struct wlr_surface *surface,
                                        struct wlr_keyboard *kb) {
    wlr_seat_keyboard_notify_enter(seat, surface,
        kb->keycodes, kb->num_keycodes, &kb->modifiers);
}

struct wl_signal *helper_seat_get_request_set_cursor(struct wlr_seat *seat) {
    return &seat->events.request_set_cursor;
}

/* ── Seat selection (clipboard) helpers ─────────────────────────── */

struct wl_signal *helper_seat_get_request_set_selection(struct wlr_seat *seat) {
    return &seat->events.request_set_selection;
}

struct wl_signal *helper_seat_get_request_set_primary_selection(struct wlr_seat *seat) {
    return &seat->events.request_set_primary_selection;
}

struct wlr_data_source *helper_selection_event_get_source(void *event) {
    struct wlr_seat_request_set_selection_event *e = event;
    return e->source;
}

uint32_t helper_selection_event_get_serial(void *event) {
    struct wlr_seat_request_set_selection_event *e = event;
    return e->serial;
}

struct wlr_primary_selection_source *helper_primary_selection_event_get_source(void *event) {
    struct wlr_seat_request_set_primary_selection_event *e = event;
    return e->source;
}

uint32_t helper_primary_selection_event_get_serial(void *event) {
    struct wlr_seat_request_set_primary_selection_event *e = event;
    return e->serial;
}

/* ── Drag-and-drop helpers ─────────────────────────────────────── */

struct wl_signal *helper_seat_get_start_drag(struct wlr_seat *seat) {
    return &seat->events.start_drag;
}

struct wlr_drag_icon *helper_drag_get_icon(void *drag) {
    struct wlr_drag *d = drag;
    return d->icon;  /* can be NULL */
}

struct wlr_surface *helper_drag_get_focus_surface(void *drag) {
    struct wlr_drag *d = drag;
    return d->focus;
}

int helper_drag_get_is_pointer_drag(void *drag) {
    struct wlr_drag *d = drag;
    return d->grab_type == WLR_DRAG_GRAB_KEYBOARD_POINTER ? 1 : 0;
}

struct wlr_surface *helper_drag_icon_get_surface(void *icon) {
    struct wlr_drag_icon *di = icon;
    return di->surface;
}

struct wl_signal *helper_drag_icon_get_destroy(void *icon) {
    struct wlr_drag_icon *di = icon;
    return &di->events.destroy;
}

struct wlr_scene_tree *helper_scene_drag_icon_create(
    struct wlr_scene_tree *parent, struct wlr_drag_icon *icon) {
    return wlr_scene_drag_icon_create(parent, icon);
}

/* ── Cursor helpers ─────────────────────────────────────────────────── */

struct wl_signal *helper_cursor_get_motion(struct wlr_cursor *cursor) {
    return &cursor->events.motion;
}

struct wl_signal *helper_cursor_get_motion_absolute(struct wlr_cursor *cursor) {
    return &cursor->events.motion_absolute;
}

struct wl_signal *helper_cursor_get_button(struct wlr_cursor *cursor) {
    return &cursor->events.button;
}

struct wl_signal *helper_cursor_get_axis(struct wlr_cursor *cursor) {
    return &cursor->events.axis;
}

struct wl_signal *helper_cursor_get_frame(struct wlr_cursor *cursor) {
    return &cursor->events.frame;
}

double helper_cursor_get_x(struct wlr_cursor *cursor) {
    return cursor->x;
}

double helper_cursor_get_y(struct wlr_cursor *cursor) {
    return cursor->y;
}

/* Cursor set_cursor event accessors */
struct wlr_surface *helper_set_cursor_event_get_surface(void *event) {
    struct wlr_seat_pointer_request_set_cursor_event *e = event;
    return e->surface;
}

int32_t helper_set_cursor_event_get_hotspot_x(void *event) {
    struct wlr_seat_pointer_request_set_cursor_event *e = event;
    return e->hotspot_x;
}

int32_t helper_set_cursor_event_get_hotspot_y(void *event) {
    struct wlr_seat_pointer_request_set_cursor_event *e = event;
    return e->hotspot_y;
}

/* Xcursor manager for default cursor */
void helper_xcursor_manager_set_cursor_image(struct wlr_xcursor_manager *mgr,
                                              const char *name,
                                              struct wlr_cursor *cursor) {
    wlr_cursor_set_xcursor(cursor, mgr, name);
}

/* Pointer event accessors */
double helper_pointer_motion_get_dx(void *event) {
    struct wlr_pointer_motion_event *e = event;
    return e->delta_x;
}

double helper_pointer_motion_get_dy(void *event) {
    struct wlr_pointer_motion_event *e = event;
    return e->delta_y;
}

uint32_t helper_pointer_motion_get_time(void *event) {
    struct wlr_pointer_motion_event *e = event;
    return e->time_msec;
}

struct wlr_input_device *helper_pointer_motion_get_device(void *event) {
    struct wlr_pointer_motion_event *e = event;
    return &e->pointer->base;
}

double helper_pointer_motion_absolute_get_x(void *event) {
    struct wlr_pointer_motion_absolute_event *e = event;
    return e->x;
}

double helper_pointer_motion_absolute_get_y(void *event) {
    struct wlr_pointer_motion_absolute_event *e = event;
    return e->y;
}

uint32_t helper_pointer_motion_absolute_get_time(void *event) {
    struct wlr_pointer_motion_absolute_event *e = event;
    return e->time_msec;
}

struct wlr_input_device *helper_pointer_motion_absolute_get_device(void *event) {
    struct wlr_pointer_motion_absolute_event *e = event;
    return &e->pointer->base;
}

uint32_t helper_pointer_button_get_button(void *event) {
    struct wlr_pointer_button_event *e = event;
    return e->button;
}

uint32_t helper_pointer_button_get_state(void *event) {
    struct wlr_pointer_button_event *e = event;
    return e->state;
}

uint32_t helper_pointer_button_get_time(void *event) {
    struct wlr_pointer_button_event *e = event;
    return e->time_msec;
}

/* ── Pointer axis (scroll) event accessors ─────────────────────────── */

uint32_t helper_pointer_axis_get_time(void *event) {
    struct wlr_pointer_axis_event *e = event;
    return e->time_msec;
}

uint32_t helper_pointer_axis_get_orientation(void *event) {
    struct wlr_pointer_axis_event *e = event;
    return e->orientation;
}

double helper_pointer_axis_get_delta(void *event) {
    struct wlr_pointer_axis_event *e = event;
    return e->delta;
}

int32_t helper_pointer_axis_get_delta_discrete(void *event) {
    struct wlr_pointer_axis_event *e = event;
    return e->delta_discrete;
}

uint32_t helper_pointer_axis_get_source(void *event) {
    struct wlr_pointer_axis_event *e = event;
    return e->source;
}

uint32_t helper_pointer_axis_get_relative_direction(void *event) {
    struct wlr_pointer_axis_event *e = event;
    return e->relative_direction;
}

/* ── Scene background rect ──────────────────────────────────────────── */

struct wlr_scene_rect *helper_scene_rect_create(struct wlr_scene_tree *parent,
                                                  int width, int height,
                                                  float r, float g, float b, float a) {
    float color[4] = { r, g, b, a };
    return wlr_scene_rect_create(parent, width, height, color);
}

/* ── XKB keysym helpers ─────────────────────────────────────────────── */

/* Get keysyms for a key event (modified by current state), returns count */
int helper_keyboard_get_keysyms(struct wlr_keyboard *kb, uint32_t keycode,
                                 const xkb_keysym_t **syms) {
    return xkb_state_key_get_syms(kb->xkb_state, keycode + 8, syms);
}

/* Get the unmodified keysym for a key (layout level 0, ignoring Shift etc.)
 * This is needed for keybinding dispatch: Super+Shift+1 should match '1',
 * not '!' which is the Shift-modified keysym. */
xkb_keysym_t helper_keyboard_get_raw_keysym(struct wlr_keyboard *kb, uint32_t keycode) {
    xkb_keycode_t xkb_keycode = keycode + 8;
    xkb_layout_index_t layout = xkb_state_key_get_layout(kb->xkb_state, xkb_keycode);
    const xkb_keysym_t *syms;
    int count = xkb_keymap_key_get_syms_by_level(kb->keymap, xkb_keycode, layout, 0, &syms);
    if (count > 0) return syms[0];
    return XKB_KEY_NoSymbol;
}

/* Force-send an xdg configure event */
uint32_t helper_xdg_surface_schedule_configure(struct wlr_xdg_toplevel *toplevel) {
    return wlr_xdg_surface_schedule_configure(toplevel->base);
}

/* ── Interactive move/resize helpers ────────────────────────────────── */

uint32_t helper_xdg_toplevel_request_resize_get_edges(void *event) {
    struct wlr_xdg_toplevel_resize_event *e = event;
    return e->edges;
}

int helper_scene_node_get_x(struct wlr_scene_node *node) {
    return node->x;
}

int helper_scene_node_get_y(struct wlr_scene_node *node) {
    return node->y;
}

int helper_xdg_toplevel_get_width(struct wlr_xdg_toplevel *toplevel) {
    struct wlr_box geo;
    wlr_xdg_surface_get_geometry(toplevel->base, &geo);
    return geo.width;
}

int helper_xdg_toplevel_get_height(struct wlr_xdg_toplevel *toplevel) {
    struct wlr_box geo;
    wlr_xdg_surface_get_geometry(toplevel->base, &geo);
    return geo.height;
}

/* ── Damage tracking helpers ────────────────────────────────────────── */

/* Extract damage rectangles from output state into a flat int array.
   Each rect is 4 ints (x, y, w, h). Returns number of rects extracted. */
int helper_output_state_extract_damage(struct wlr_output_state *state,
                                        int *buf, int max_rects) {
    int n;
    pixman_box32_t *boxes = pixman_region32_rectangles(&state->damage, &n);
    if (n > max_rects) n = max_rects;
    for (int i = 0; i < n; i++) {
        buf[i*4 + 0] = boxes[i].x1;
        buf[i*4 + 1] = boxes[i].y1;
        buf[i*4 + 2] = boxes[i].x2 - boxes[i].x1;
        buf[i*4 + 3] = boxes[i].y2 - boxes[i].y1;
    }
    return n;
}

/* ── Multi-monitor helpers ──────────────────────────────────────────── */

int helper_output_layout_get_x(struct wlr_output_layout *layout,
                                struct wlr_output *output) {
    struct wlr_output_layout_output *lo = wlr_output_layout_get(layout, output);
    return lo ? lo->x : 0;
}

int helper_output_layout_get_y(struct wlr_output_layout *layout,
                                struct wlr_output *output) {
    struct wlr_output_layout_output *lo = wlr_output_layout_get(layout, output);
    return lo ? lo->y : 0;
}

/* ── Layer shell helpers ────────────────────────────────────────────── */

struct wlr_layer_shell_v1 *helper_layer_shell_create(struct wl_display *display) {
    return wlr_layer_shell_v1_create(display, 4);
}

struct wl_signal *helper_layer_shell_get_new_surface(struct wlr_layer_shell_v1 *shell) {
    return &shell->events.new_surface;
}

/* Layer surface state accessors (read from current) */

const char *helper_layer_surface_get_namespace(struct wlr_layer_surface_v1 *ls) {
    return ls->namespace;
}

uint32_t helper_layer_surface_get_layer(struct wlr_layer_surface_v1 *ls) {
    return ls->current.layer;
}

uint32_t helper_layer_surface_get_pending_layer(struct wlr_layer_surface_v1 *ls) {
    return ls->pending.layer;
}

uint32_t helper_layer_surface_get_anchor(struct wlr_layer_surface_v1 *ls) {
    return ls->current.anchor;
}

int32_t helper_layer_surface_get_exclusive_zone(struct wlr_layer_surface_v1 *ls) {
    return ls->current.exclusive_zone;
}

uint32_t helper_layer_surface_get_keyboard_interactive(struct wlr_layer_surface_v1 *ls) {
    return ls->current.keyboard_interactive;
}

struct wlr_output *helper_layer_surface_get_output(struct wlr_layer_surface_v1 *ls) {
    return ls->output;
}

void helper_layer_surface_set_output(struct wlr_layer_surface_v1 *ls,
                                      struct wlr_output *output) {
    ls->output = output;
}

/* Layer surface signals */

struct wl_signal *helper_layer_surface_get_destroy(struct wlr_layer_surface_v1 *ls) {
    return &ls->events.destroy;
}

struct wl_signal *helper_layer_surface_get_surface_commit(struct wlr_layer_surface_v1 *ls) {
    return &ls->surface->events.commit;
}

/* Scene layer surface integration */

void helper_scene_layer_surface_configure(
    struct wlr_scene_layer_surface_v1 *sls,
    int full_x, int full_y, int full_w, int full_h,
    int *usable_x, int *usable_y, int *usable_w, int *usable_h) {
    struct wlr_box full = { full_x, full_y, full_w, full_h };
    struct wlr_box usable = { *usable_x, *usable_y, *usable_w, *usable_h };
    wlr_scene_layer_surface_v1_configure(sls, &full, &usable);
    *usable_x = usable.x;
    *usable_y = usable.y;
    *usable_w = usable.width;
    *usable_h = usable.height;
}

struct wlr_scene_tree *helper_scene_layer_surface_get_tree(
    struct wlr_scene_layer_surface_v1 *sls) {
    return sls->tree;
}

/* ── Wayland event loop fd registration ────────────────────────────── */

struct wl_event_source *helper_event_loop_add_fd(
    struct wl_event_loop *loop,
    int fd,
    wl_event_loop_fd_func_t func,
    void *data)
{
    return wl_event_loop_add_fd(loop, fd, WL_EVENT_READABLE, func, data);
}

/* ── Pointer constraints helpers ───────────────────────────────── */

struct wl_signal *helper_pointer_constraints_get_new_constraint(
    struct wlr_pointer_constraints_v1 *pc) {
    return &pc->events.new_constraint;
}

struct wlr_surface *helper_pointer_constraint_get_surface(
    struct wlr_pointer_constraint_v1 *constraint) {
    return constraint->surface;
}

uint32_t helper_pointer_constraint_get_type(
    struct wlr_pointer_constraint_v1 *constraint) {
    return constraint->type;
}

struct wl_signal *helper_pointer_constraint_get_set_region(
    struct wlr_pointer_constraint_v1 *constraint) {
    return &constraint->events.set_region;
}

struct wl_signal *helper_pointer_constraint_get_destroy(
    struct wlr_pointer_constraint_v1 *constraint) {
    return &constraint->events.destroy;
}

/* ── Relative pointer helpers ──────────────────────────────────── */

double helper_pointer_motion_get_unaccel_dx(void *event) {
    struct wlr_pointer_motion_event *e = event;
    return e->unaccel_dx;
}

double helper_pointer_motion_get_unaccel_dy(void *event) {
    struct wlr_pointer_motion_event *e = event;
    return e->unaccel_dy;
}

/* ── Pointer gestures helpers ──────────────────────────────────── */

struct wl_signal *helper_cursor_get_swipe_begin(struct wlr_cursor *cursor) {
    return &cursor->events.swipe_begin;
}
struct wl_signal *helper_cursor_get_swipe_update(struct wlr_cursor *cursor) {
    return &cursor->events.swipe_update;
}
struct wl_signal *helper_cursor_get_swipe_end(struct wlr_cursor *cursor) {
    return &cursor->events.swipe_end;
}
struct wl_signal *helper_cursor_get_pinch_begin(struct wlr_cursor *cursor) {
    return &cursor->events.pinch_begin;
}
struct wl_signal *helper_cursor_get_pinch_update(struct wlr_cursor *cursor) {
    return &cursor->events.pinch_update;
}
struct wl_signal *helper_cursor_get_pinch_end(struct wlr_cursor *cursor) {
    return &cursor->events.pinch_end;
}
struct wl_signal *helper_cursor_get_hold_begin(struct wlr_cursor *cursor) {
    return &cursor->events.hold_begin;
}
struct wl_signal *helper_cursor_get_hold_end(struct wlr_cursor *cursor) {
    return &cursor->events.hold_end;
}

uint32_t helper_swipe_begin_get_time(void *event) {
    struct wlr_pointer_swipe_begin_event *e = event;
    return e->time_msec;
}
uint32_t helper_swipe_begin_get_fingers(void *event) {
    struct wlr_pointer_swipe_begin_event *e = event;
    return e->fingers;
}
uint32_t helper_swipe_update_get_time(void *event) {
    struct wlr_pointer_swipe_update_event *e = event;
    return e->time_msec;
}
uint32_t helper_swipe_update_get_fingers(void *event) {
    struct wlr_pointer_swipe_update_event *e = event;
    return e->fingers;
}
double helper_swipe_update_get_dx(void *event) {
    struct wlr_pointer_swipe_update_event *e = event;
    return e->dx;
}
double helper_swipe_update_get_dy(void *event) {
    struct wlr_pointer_swipe_update_event *e = event;
    return e->dy;
}
uint32_t helper_swipe_end_get_time(void *event) {
    struct wlr_pointer_swipe_end_event *e = event;
    return e->time_msec;
}
int helper_swipe_end_get_cancelled(void *event) {
    struct wlr_pointer_swipe_end_event *e = event;
    return e->cancelled ? 1 : 0;
}

uint32_t helper_pinch_begin_get_time(void *event) {
    struct wlr_pointer_pinch_begin_event *e = event;
    return e->time_msec;
}
uint32_t helper_pinch_begin_get_fingers(void *event) {
    struct wlr_pointer_pinch_begin_event *e = event;
    return e->fingers;
}
uint32_t helper_pinch_update_get_time(void *event) {
    struct wlr_pointer_pinch_update_event *e = event;
    return e->time_msec;
}
uint32_t helper_pinch_update_get_fingers(void *event) {
    struct wlr_pointer_pinch_update_event *e = event;
    return e->fingers;
}
double helper_pinch_update_get_dx(void *event) {
    struct wlr_pointer_pinch_update_event *e = event;
    return e->dx;
}
double helper_pinch_update_get_dy(void *event) {
    struct wlr_pointer_pinch_update_event *e = event;
    return e->dy;
}
double helper_pinch_update_get_scale(void *event) {
    struct wlr_pointer_pinch_update_event *e = event;
    return e->scale;
}
double helper_pinch_update_get_rotation(void *event) {
    struct wlr_pointer_pinch_update_event *e = event;
    return e->rotation;
}
uint32_t helper_pinch_end_get_time(void *event) {
    struct wlr_pointer_pinch_end_event *e = event;
    return e->time_msec;
}
int helper_pinch_end_get_cancelled(void *event) {
    struct wlr_pointer_pinch_end_event *e = event;
    return e->cancelled ? 1 : 0;
}

uint32_t helper_hold_begin_get_time(void *event) {
    struct wlr_pointer_hold_begin_event *e = event;
    return e->time_msec;
}
uint32_t helper_hold_begin_get_fingers(void *event) {
    struct wlr_pointer_hold_begin_event *e = event;
    return e->fingers;
}
uint32_t helper_hold_end_get_time(void *event) {
    struct wlr_pointer_hold_end_event *e = event;
    return e->time_msec;
}
int helper_hold_end_get_cancelled(void *event) {
    struct wlr_pointer_hold_end_event *e = event;
    return e->cancelled ? 1 : 0;
}

